package com.vijay.campaign.controller;

import com.vijay.campaign.model.ChatRequest;
import com.vijay.campaign.model.ChatResponse;
import com.vijay.campaign.service.CampaignChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/campaign-chat")
@RequiredArgsConstructor
public class CampaignChatController {

    private final CampaignChatService campaignChatService;

    /**
     * Send a message in a campaign creation conversation.
     *
     * Headers:  Authorization: Bearer <token>
     * Body:     { "sessionId": "...", "message": "...", "businessAccountId": "...", "adAccountId": "..." }
     */
    @PostMapping
    public ChatResponse chat(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestBody ChatRequest request) {

        String reply = campaignChatService.chat(
                request.sessionId(),
                request.message(),
                request.businessAccountId(),
                request.adAccountId(),
                request.brandId(),
                authorizationHeader);

        String state = stateLabel(request.sessionId());
        return new ChatResponse(request.sessionId(), reply, state);
    }

    /**
     * Reset a session to start over.
     *   DELETE /api/campaign-chat/{sessionId}
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> reset(@PathVariable String sessionId) {
        campaignChatService.resetSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Check current session state.
     *   GET /api/campaign-chat/{sessionId}/state
     */
    @GetMapping("/{sessionId}/state")
    public ResponseEntity<String> state(@PathVariable String sessionId) {
        var state = campaignChatService.getState(sessionId);
        if (state == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(state.name());
    }

    private String stateLabel(String sessionId) {
        var state = campaignChatService.getState(sessionId);
        return state != null ? state.name() : "COLLECTING";
    }
}
