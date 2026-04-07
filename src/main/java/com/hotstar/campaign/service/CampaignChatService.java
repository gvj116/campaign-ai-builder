package com.hotstar.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotstar.campaign.model.AiExtraction;
import com.hotstar.campaign.model.CampaignSession;
import com.hotstar.campaign.model.CampaignSession.State;
import com.hotstar.campaign.model.FieldMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final FieldRegistry fieldRegistry;
    private final PayloadAssembler payloadAssembler;
    private final CampaignApiClient campaignApiClient;
    private final ObjectMapper objectMapper;

    // In-memory session store — swap for Redis in production
    private final Map<String, CampaignSession> sessions = new ConcurrentHashMap<>();

    // Keys that belong to the AI extraction envelope — never store these as campaign field values
    private static final java.util.Set<String> AI_ENVELOPE_KEYS =
            java.util.Set.of("readyToSubmit", "reply", "action", "confirmed");


    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public String chat(String sessionId, String userMessage,
                       String businessAccountId, String adAccountId,
                       String brandId, String authorizationHeader) {
        CampaignSession session = sessions.computeIfAbsent(sessionId, CampaignSession::new);

        // Store account context from first request (or update if provided again)
        if (businessAccountId != null) session.setBusinessAccountId(businessAccountId);
        if (adAccountId != null)       session.setAdAccountId(adAccountId);
        if (brandId != null)           session.setBrandId(brandId);
        if (authorizationHeader != null) session.setAuthorizationHeader(authorizationHeader);

        return switch (session.getState()) {
            case SUBMITTED  -> "This campaign has already been submitted. Start a new session to create another.";
            case CONFIRMING -> handleConfirming(session, sessionId, userMessage);
            case COLLECTING -> handleCollecting(session, sessionId, userMessage);
        };
    }

    public void resetSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("Session {} reset", sessionId);
    }

    public CampaignSession.State getState(String sessionId) {
        CampaignSession session = sessions.get(sessionId);
        return session != null ? session.getState() : null;
    }

    // -------------------------------------------------------------------------
    // COLLECTING phase — gather field values from the user
    // -------------------------------------------------------------------------

    private String handleCollecting(CampaignSession session, String sessionId, String userMessage) {
        String systemPrompt = buildCollectingPrompt(session);

        String aiRaw = callClaude(sessionId, systemPrompt, userMessage);
        AiExtraction extraction = parseAiResponse(aiRaw);

        // Apply any field updates the AI extracted — skip AI envelope keys (readyToSubmit etc.)
        if (extraction.getUpdates() != null && !extraction.getUpdates().isEmpty()) {
            extraction.getUpdates().entrySet().stream()
                    .filter(e -> !AI_ENVELOPE_KEYS.contains(e.getKey()))
                    .forEach(e -> session.getUserValues().put(e.getKey(), e.getValue()));
            log.info("Session {} — updated fields: {}", sessionId,
                    extraction.getUpdates().keySet().stream()
                            .filter(k -> !AI_ENVELOPE_KEYS.contains(k)).toList());
        }

        // User signalled they're done — show summary and ask for confirmation
        if (extraction.isReadyToSubmit()) {
            session.setState(State.CONFIRMING);
            return buildSummary(session);
        }

        return extraction.getReply() != null ? extraction.getReply() : stripThinkTags(aiRaw);
    }

    // -------------------------------------------------------------------------
    // CONFIRMING phase — show summary, wait for confirmation or changes
    // -------------------------------------------------------------------------

    private String handleConfirming(CampaignSession session, String sessionId, String userMessage) {
        String systemPrompt = """
                The user has just reviewed a campaign summary.
                Determine their intent from their message and respond with ONLY valid JSON:

                If they confirm (yes / confirm / submit / looks good / go ahead / proceed):
                  { "action": "CONFIRM", "reply": "Great! Submitting your campaign now...", "updates": {} }

                If they want to make changes (change X / update Y / actually ...):
                  { "action": "MODIFY", "reply": "Got it, updating that now.", "updates": { "fieldPath": value } }
                  Use the exact field path keys from the campaign field list.

                If they want to cancel / start over:
                  { "action": "CANCEL", "reply": "No problem, session reset. Let's start fresh!", "updates": {} }
                """;

        String aiRaw = callClaude(sessionId, systemPrompt, userMessage);
        AiExtraction extraction = parseAiResponse(aiRaw);

        String action = extraction.getAction() != null ? extraction.getAction().toUpperCase() : "";

        return switch (action) {
            case "CONFIRM" -> submitCampaign(session, extraction.getReply());
            case "MODIFY" -> {
                if (extraction.getUpdates() != null && !extraction.getUpdates().isEmpty()) {
                    session.getUserValues().putAll(extraction.getUpdates());
                    log.info("Session {} — modified fields: {}", sessionId, extraction.getUpdates().keySet());
                }
                // Stay in CONFIRMING and show updated summary
                yield extraction.getReply() + "\n\n" + buildSummary(session);
            }
            case "CANCEL" -> {
                resetSession(session.getSessionId());
                yield extraction.getReply() != null
                        ? extraction.getReply()
                        : "Session reset. Start whenever you're ready!";
            }
            default -> {
                // Fallback: couldn't parse action, just show summary again
                yield (extraction.getReply() != null ? extraction.getReply() + "\n\n" : "")
                        + buildSummary(session);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Submit
    // -------------------------------------------------------------------------

    private String submitCampaign(CampaignSession session, String confirmReply) {
        try {
            com.fasterxml.jackson.databind.JsonNode payload = payloadAssembler.assemble(session);
            log.info("Submitting campaign for session {} (businessAccount={}, adAccount={})",
                    session.getSessionId(), session.getBusinessAccountId(), session.getAdAccountId());
            log.info("Assembled payload:\n{}", payload.toPrettyString());

            com.fasterxml.jackson.databind.JsonNode apiResponse = campaignApiClient.postWorkflow(
                    session.getBusinessAccountId(),
                    session.getAdAccountId(),
                    payload,
                    session.getAuthorizationHeader());

            String campaignId = CampaignApiClient.extractCampaignId(apiResponse);
            session.setState(State.SUBMITTED);

            String idInfo = campaignId != null ? " Campaign ID: " + campaignId : "";
            return (confirmReply != null ? confirmReply + "\n\n" : "")
                    + "Campaign submitted successfully!" + idInfo + "\n\nFull response:\n" + apiResponse.toPrettyString();

        } catch (com.hotstar.campaign.exception.LegoWorkflowException e) {
            log.error("Campaign submission failed (HTTP {}): {}", e.getStatusCode(), e.getResponseBody());
            session.setState(State.SUBMITTED);
            return "Submission failed (HTTP " + e.getStatusCode() + "): " + e.getResponseBody();
        } catch (Exception e) {
            log.error("Campaign submission failed for session {}", session.getSessionId(), e);
            session.setState(State.SUBMITTED);
            return "Submission failed: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Summary builder
    // -------------------------------------------------------------------------

    private String buildSummary(CampaignSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("Here's a summary of your campaign:\n\n");

        // Show all user-provided values directly (covers dynamic paths like adSets[1].name)
        sb.append("**Your inputs:**\n");
        if (session.getUserValues().isEmpty()) {
            sb.append("- (none provided yet)\n");
        } else {
            // Try to show human-readable labels for known fields; fall back to raw path
            Map<String, String> pathToLabel = fieldRegistry.getAskableFields().stream()
                    .collect(Collectors.toMap(FieldMeta::getPath, FieldMeta::getLabel));
            session.getUserValues().forEach((path, value) -> {
                String label = pathToLabel.getOrDefault(path, path);
                sb.append("- ").append(label).append(": ").append(value).append("\n");
            });
        }

        // Show registered fields that will use defaults
        sb.append("\n**Filling with defaults:**\n");
        for (FieldMeta field : fieldRegistry.getAskableFields()) {
            if (!session.getUserValues().containsKey(field.getPath())) {
                String defaultVal = resolveDefaultDisplayValue(field.getPath(), session);
                sb.append("- ").append(field.getLabel()).append(": ").append(defaultVal).append("\n");
            }
        }

        sb.append("\nReady to submit? Type **confirm** to go ahead, or tell me what you'd like to change.");
        return sb.toString();
    }

    /** Resolves display value for summary — shows auto-filled session values instead of blank template defaults. */
    private String resolveDefaultDisplayValue(String path, CampaignSession session) {
        // Fields auto-filled from session context — show the actual value
        if ("ownerReferenceId".equals(path) || path.contains("creativeRequest.ownerReferenceId")) {
            return session.getAdAccountId() != null ? session.getAdAccountId() : "(empty)";
        }
        if ("brandId".equals(path)) {
            return session.getBrandId() != null ? session.getBrandId() : "(empty)";
        }
        return payloadAssembler.getDefaultDisplayValue(path);
    }

    // -------------------------------------------------------------------------
    // System prompt for COLLECTING phase
    // -------------------------------------------------------------------------

    private String buildCollectingPrompt(CampaignSession session) {
        String fieldLines = fieldRegistry.getAskableFields().stream()
                .map(f -> {
                    Object currentValue = session.getUserValues().get(f.getPath());
                    String valueStr = currentValue != null
                            ? currentValue + " (set by you)"
                            : payloadAssembler.getDefaultDisplayValue(f.getPath()) + " (default)";
                    String optionsStr = (f.getOptions() != null && !f.getOptions().isEmpty())
                            ? " [options: " + f.getOptions().stream()
                                .map(o -> o.getLabel() != null && !o.getLabel().equals(o.getValue())
                                        ? o.getDisplayLabel() + "→" + o.getValue()
                                        : o.getValue())
                                .collect(java.util.stream.Collectors.joining(", ")) + "]"
                            : "";
                    return "  - %s (%s): %s%s".formatted(f.getLabel(), f.getPath(), valueStr, optionsStr);
                })
                .collect(Collectors.joining("\n"));

        return """
                You are a friendly campaign creation assistant — like a helpful colleague.
                Help the user set up an ad campaign through natural conversation.
                They can share details in any order, and you capture whatever they mention.

                CURRENT FIELD VALUES:
                %s

                RULES:
                - Be brief, natural, conversational. Never list out all the fields.
                - Capture whatever the user mentions. They may skip around freely.
                - CRITICAL: If the user signals they are done (says "save", "done", "submit", "that's all", "looks good", "that's everything", "go ahead", "finalize", "proceed") → you MUST set "readyToSubmit": true. Do NOT say "saved" or "finalized" without also setting readyToSubmit to true.
                - Never push the user to fill any specific field.
                - For array fields (geo, device, etc.) accept comma-separated values and return as a JSON array.
                - CRITICAL: Always use the EXACT field path shown in parentheses as the key in "updates". Never shorten or simplify paths. For example, use "adSets[0].schedule.dateRange.startDate" — NOT "adSets[0].startDate".
                - When the user provides a date, validate it before accepting. If the date is impossible (e.g. Nov 31, Feb 30, Sep 31), immediately tell them it's invalid and ask them to provide a correct date. Do NOT store an invalid date in "updates".

                MULTIPLE ADSETS / ADS:
                - If user says "create N adsets", generate N entries: adSets[0].name, adSets[1].name ... adSets[N-1].name
                  Use auto-names like "AdSet 1", "AdSet 2", etc. unless user provides names.
                - If user says "create N ads", generate N entries inside each adSet: adSets[0].ads[0].name ... adSets[0].ads[N-1].name
                  Use auto-names like "Ad 1", "Ad 2", etc. unless user provides names.
                - If user says "save" or "done" immediately after specifying counts, set readyToSubmit: true.
                - To DELETE an adset (e.g. "remove adset 3" = 3rd adset = index 2), set "adSets[2]": null in updates.

                TRACKERS:
                - AdSet-level trackers (adSets[0].trackers): array of objects with just a URL, e.g. [{"url": "https://..."}]
                - Ad-level trackers (adSets[0].ads[0].trackers): array of objects with type + URL, e.g. [{"type": "IMPRESSION", "url": "https://..."}, {"type": "CLICK", "url": "https://..."}]
                - Valid tracker types: IMPRESSION, CLICK, VIDEO_START, VIDEO_FIRST_QUARTILE, VIDEO_MIDPOINT, VIDEO_THIRD_QUARTILE, VIDEO_COMPLETE

                ALWAYS respond with ONLY valid JSON — no markdown fences, no extra text:
                {
                  "reply": "your conversational message",
                  "updates": { "fieldPath": value },
                  "readyToSubmit": true or false
                }

                readyToSubmit MUST be true if the user indicated they are done. Otherwise false.
                Use the exact field path shown in parentheses above as keys in "updates".
                Only include in "updates" the fields mentioned in the CURRENT user message.
                """.formatted(fieldLines);
    }

    // -------------------------------------------------------------------------
    // Claude call helper
    // -------------------------------------------------------------------------

    private String callClaude(String sessionId, String systemPrompt, String userMessage) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .conversationId(sessionId)
                        .build())
                .call()
                .content();
    }

    // -------------------------------------------------------------------------
    // JSON parser — handles markdown fences gracefully
    // -------------------------------------------------------------------------

    private String stripThinkTags(String text) {
        return text == null ? null : text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private AiExtraction parseAiResponse(String raw) {
        try {
            String cleaned = raw.trim();
            // Strip <think>...</think> blocks (qwen3 and other reasoning models)
            cleaned = cleaned.replaceAll("(?s)<think>.*?</think>", "").trim();
            // Strip markdown fences
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)```(?:json)?\\s*(.*?)```", "$1").trim();
            }
            return objectMapper.readValue(cleaned, AiExtraction.class);
        } catch (Exception e) {
            log.warn("Could not parse AI response as JSON, treating as plain reply: {}", raw);
            return AiExtraction.builder().reply(raw).build();
        }
    }
}
