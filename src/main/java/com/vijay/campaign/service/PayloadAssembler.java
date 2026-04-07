package com.vijay.campaign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vijay.campaign.model.CampaignSession;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class PayloadAssembler {

    private final ObjectMapper objectMapper;
    private JsonNode templateNode;

    // Matches whole adset deletion markers like "adSets[2]" (no sub-path)
    private static final Pattern ADSET_DELETION_PATTERN = Pattern.compile("^adSets\\[(\\d+)\\]$");

    @PostConstruct
    public void load() {
        try {
            var resource = new ClassPathResource("campaign-template.json");
            templateNode = objectMapper.readTree(resource.getInputStream());
            log.info("Loaded campaign-template.json");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load campaign-template.json", e);
        }
    }

    /**
     * Assembles the final payload: template defaults + user-provided values.
     * Dynamically expands adSets/ads arrays to fit whatever indexes the user provided.
     */
    public JsonNode assemble(CampaignSession session) {
        ObjectNode payload = templateNode.deepCopy();

        // First pass: expand arrays to fit all user-specified indexes
        expandArraysForUserValues(payload, session.getUserValues());

        // Second pass: apply all user-provided values (skip whole-adset deletion markers)
        for (Map.Entry<String, Object> entry : session.getUserValues().entrySet()) {
            // Skip deletion markers — handled separately below
            if (isAdSetDeletionMarker(entry.getKey(), entry.getValue())) continue;

            try {
                JsonNode value = objectMapper.valueToTree(entry.getValue());
                // If AI returned a whole array (e.g. adSets[0].ads = [...]), merge each element
                // with the template so defaults are preserved
                if (value.isArray()) {
                    value = mergeArrayWithTemplate(entry.getKey(), (ArrayNode) value);
                }
                setValueAtPath(payload, entry.getKey(), value);
            } catch (Exception e) {
                log.warn("Could not apply user value for path '{}': {}", entry.getKey(), e.getMessage());
            }
        }

        // Remove any adsets the user explicitly deleted (e.g. "adSets[2]": null)
        Set<Integer> deletionIndices = collectAdSetDeletionIndices(session.getUserValues());
        if (!deletionIndices.isEmpty()) {
            removeAdSetsByIndices((ArrayNode) payload.get("adSets"), deletionIndices);
        }

        // Auto-generate smart names for any empty name fields
        autoFillNames(payload, session);

        // Auto-fill ownerReferenceId with adAccountId if not already set by user
        String adAccountId = session.getAdAccountId();
        if (adAccountId != null && !adAccountId.isBlank()) {
            autoFillOwnerReferenceIds(payload, session, adAccountId);
        }

        // Auto-fill brandId from session context (passed in request, not collected from user)
        String brandId = session.getBrandId();
        if (brandId != null && !brandId.isBlank()) {
            autoFillBlankField(payload, session, "brandId", objectMapper.valueToTree(brandId));
        }

        // Ensure required fields that the API validates are never null/missing
        autoFillRequiredFields(payload);

        // Validate and clamp any invalid dates (e.g. Nov 31 → Nov 30)
        validateAndFixDates(payload);

        return payload;
    }

    /**
     * Returns the default value display string for a given field path.
     */
    public String getDefaultDisplayValue(String path) {
        try {
            JsonNode node = getNodeAtPath(templateNode, path);
            if (node == null || node.isNull()) return "null";
            if (node.isArray() && node.isEmpty()) return "[]";
            if (node.isTextual() && node.asText().isBlank()) return "(empty)";
            return node.toString();
        } catch (Exception e) {
            return "(default)";
        }
    }

    // -------------------------------------------------------------------------
    // AdSet deletion
    // -------------------------------------------------------------------------

    /** Returns true if this userValues entry is a whole-adset deletion marker. */
    private boolean isAdSetDeletionMarker(String key, Object value) {
        return value == null && ADSET_DELETION_PATTERN.matcher(key).matches();
    }

    /**
     * Collects adset indices that the user wants removed.
     * Looks for entries like { "adSets[2]": null } in userValues.
     */
    private Set<Integer> collectAdSetDeletionIndices(Map<String, Object> userValues) {
        Set<Integer> indices = new TreeSet<>();
        for (Map.Entry<String, Object> entry : userValues.entrySet()) {
            if (entry.getValue() == null) {
                Matcher m = ADSET_DELETION_PATTERN.matcher(entry.getKey());
                if (m.matches()) {
                    indices.add(Integer.parseInt(m.group(1)));
                    log.info("Scheduled adSets[{}] for deletion", m.group(1));
                }
            }
        }
        return indices;
    }

    /**
     * Removes adset elements at the specified indices (in reverse order to preserve positions).
     */
    private void removeAdSetsByIndices(ArrayNode adSetsArray, Set<Integer> indices) {
        List<Integer> sorted = new ArrayList<>(indices);
        sorted.sort(Comparator.reverseOrder());
        for (int idx : sorted) {
            if (idx < adSetsArray.size()) {
                adSetsArray.remove(idx);
                log.info("Removed adSets[{}] as requested", idx);
            } else {
                log.warn("Tried to remove adSets[{}] but array only has {} elements — skipping", idx, adSetsArray.size());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Date validation — clamp invalid dates (e.g. Nov 31 → Nov 30)
    // -------------------------------------------------------------------------

    /**
     * Recursively walks the entire assembled payload and clamps any invalid date values
     * (YYYY-MM-DD pattern) to the last valid day of the month.
     *
     * Using recursive traversal instead of fixed paths means we catch dates wherever
     * the LLM places them — including misplaced paths like adSets[0].startDate
     * instead of the correct adSets[0].schedule.dateRange.startDate.
     */
    private void validateAndFixDates(ObjectNode payload) {
        fixDatesRecursive(payload, "");
    }

    private void fixDatesRecursive(JsonNode node, String path) {
        if (node == null) return;

        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                JsonNode child = entry.getValue();
                if (child.isTextual()) {
                    String val = child.asText();
                    if (looksLikeDate(val)) {
                        String fixed = clampDateIfInvalid(val, childPath);
                        if (fixed != null) {
                            ((ObjectNode) node).put(entry.getKey(), fixed);
                        }
                    }
                } else {
                    fixDatesRecursive(child, childPath);
                }
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                fixDatesRecursive(node.get(i), path + "[" + i + "]");
            }
        }
    }

    /** Returns true if the string looks like a YYYY-MM-DD date (with optional time suffix). */
    private boolean looksLikeDate(String value) {
        return value != null && value.matches("\\d{4}-\\d{2}-\\d{2}.*");
    }

    /**
     * Validates a date string. If the day-of-month exceeds the month's maximum, clamps it.
     * Returns the fixed string, or null if no fix was needed.
     * Skips 1970 placeholder defaults.
     */
    private String clampDateIfInvalid(String raw, String fieldPath) {
        if (raw.isBlank() || raw.startsWith("1970-")) return null;

        try {
            String datePart = raw.contains("T") ? raw.substring(0, raw.indexOf('T')) : raw;
            String[] parts = datePart.split("-");
            if (parts.length != 3) return null;

            int year  = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day   = Integer.parseInt(parts[2]);

            YearMonth ym = YearMonth.of(year, month);
            if (day > ym.lengthOfMonth()) {
                int clampedDay = ym.lengthOfMonth();
                log.warn("Invalid date '{}' at '{}' — day {} exceeds {} days in {}/{}; clamping to {}",
                        raw, fieldPath, day, ym.lengthOfMonth(), month, year, clampedDay);

                String fixedDate = String.format("%04d-%02d-%02d", year, month, clampedDay);
                return raw.contains("T") ? fixedDate + raw.substring(raw.indexOf('T')) : fixedDate;
            }
        } catch (Exception e) {
            log.warn("Could not validate date at '{}' (value='{}'): {}", fieldPath, raw, e.getMessage());
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Array merging — preserves template defaults when AI returns whole arrays
    // -------------------------------------------------------------------------

    /**
     * When AI returns a whole array value (e.g. adSets[0].ads = [{"name":"Ad 1"}, ...]),
     * merge each element with the corresponding template element so defaults are kept.
     */
    private ArrayNode mergeArrayWithTemplate(String path, ArrayNode providedArray) {
        // Find the template element for this array path (e.g. "adSets[0].ads[0]")
        String templateElementPath = path + "[0]";
        JsonNode templateElement = getNodeAtPath(templateNode, templateElementPath);
        if (templateElement == null || !templateElement.isObject()) {
            return providedArray; // No template to merge with, use as-is
        }

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode provided : providedArray) {
            ObjectNode merged = templateElement.deepCopy();
            if (provided.isObject()) {
                provided.fields().forEachRemaining(field -> merged.set(field.getKey(), field.getValue()));
            }
            result.add(merged);
        }
        log.debug("Merged {} array elements with template for path '{}'", result.size(), path);
        return result;
    }

    // -------------------------------------------------------------------------
    // Array expansion
    // -------------------------------------------------------------------------

    /**
     * Scans all user-provided paths and expands adSets / ads arrays in the payload
     * to ensure all referenced indexes exist (filled with deep-clones of the template entry).
     */
    private void expandArraysForUserValues(ObjectNode payload, Map<String, Object> userValues) {
        // Find the max adSet index and max ads index per adSet
        int maxAdSetIdx = 0;
        int maxAdIdx = 0;

        for (String path : userValues.keySet()) {
            List<Object> segments = parsePath(path);
            for (int i = 0; i < segments.size(); i++) {
                if ("adSets".equals(segments.get(i)) && i + 1 < segments.size()
                        && segments.get(i + 1) instanceof Integer idx) {
                    maxAdSetIdx = Math.max(maxAdSetIdx, idx);
                }
                if ("ads".equals(segments.get(i)) && i + 1 < segments.size()
                        && segments.get(i + 1) instanceof Integer idx) {
                    maxAdIdx = Math.max(maxAdIdx, idx);
                }
            }
        }

        ArrayNode adSetsArray = (ArrayNode) payload.get("adSets");
        JsonNode adSetTemplate = templateNode.get("adSets").get(0);

        // Expand adSets array
        while (adSetsArray.size() <= maxAdSetIdx) {
            adSetsArray.add(adSetTemplate.deepCopy());
            log.debug("Expanded adSets array to size {}", adSetsArray.size());
        }

        // Expand ads array inside each adSet
        JsonNode adTemplate = templateNode.get("adSets").get(0).get("ads").get(0);
        for (int i = 0; i <= maxAdSetIdx; i++) {
            ArrayNode adsArray = (ArrayNode) adSetsArray.get(i).get("ads");
            while (adsArray.size() <= maxAdIdx) {
                adsArray.add(adTemplate.deepCopy());
                log.debug("Expanded adSets[{}].ads array to size {}", i, adsArray.size());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Auto name fill
    // -------------------------------------------------------------------------

    private void autoFillNames(ObjectNode payload, CampaignSession session) {
        String dateSuffix = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMMyyyy")).toUpperCase();

        autoFillNameField(payload, session, "name", "Campaign_" + dateSuffix);

        ArrayNode adSets = (ArrayNode) payload.get("adSets");
        for (int i = 0; i < adSets.size(); i++) {
            String adSetNamePath = "adSets[" + i + "].name";
            autoFillNameField(payload, session, adSetNamePath, "AdSet" + (i + 1) + "_" + dateSuffix);

            ArrayNode ads = (ArrayNode) adSets.get(i).get("ads");
            for (int j = 0; j < ads.size(); j++) {
                String adNamePath = "adSets[" + i + "].ads[" + j + "].name";
                autoFillNameField(payload, session, adNamePath, "Ad" + (i + 1) + "_" + (j + 1) + "_" + dateSuffix);
            }
        }
    }

    /**
     * Ensures fields that the API validates as required are never null/missing,
     * regardless of what the LLM returned or how the merge/replace happened.
     *
     * Campaign-level required: name, ownerReferenceId, brandId, servingPlatform, objective, countryGroupId
     *   → all covered by template defaults or autoFillNames / autoFillOwnerReferenceIds.
     *
     * AdSet-level required: action (must not be null)
     * Ad-level required:    action, adType (must not be null)
     * creativeRequest:      type (Jackson discriminator — must not be null)
     */
    private void autoFillRequiredFields(ObjectNode payload) {
        // Campaign-level action
        ensureField(payload, "action", "OFF");

        ArrayNode adSets = (ArrayNode) payload.get("adSets");
        if (adSets == null) return;

        for (int i = 0; i < adSets.size(); i++) {
            String adSetPrefix = "adSets[" + i + "]";

            ensureField(payload, adSetPrefix + ".action",            "OFF");
            ensureField(payload, adSetPrefix + ".placementStrategy", "AUTOMATIC");

            ArrayNode ads = (ArrayNode) adSets.get(i).get("ads");
            if (ads == null) continue;

            for (int j = 0; j < ads.size(); j++) {
                String adPrefix = adSetPrefix + ".ads[" + j + "]";

                ensureField(payload, adPrefix + ".action",     "OFF");
                ensureField(payload, adPrefix + ".adType",     "VIDEO");
                ensureField(payload, adPrefix + ".streamType", "VOD");

                // creativeRequest.type MUST always match adType (Jackson polymorphic discriminator)
                // Always force-set — never leave it as template default "VIDEO" when adType differs
                String adTypePath = adPrefix + ".adType";
                JsonNode adTypeNode = getNodeAtPath(payload, adTypePath);
                String adTypeVal = (adTypeNode != null && adTypeNode.isTextual()) ? adTypeNode.asText() : "VIDEO";
                forceField(payload, adPrefix + ".creativeRequest.type", adTypeVal);
            }
        }
    }

    /** Always sets a field at path, overriding any existing value. */
    private void forceField(ObjectNode root, String path, String value) {
        try {
            setValueAtPath(root, path, objectMapper.valueToTree(value));
            log.debug("forceField: set '{}' = '{}'", path, value);
        } catch (Exception e) {
            log.warn("forceField: could not set '{}': {}", path, e.getMessage());
        }
    }

    /** Sets a field at path only if it is currently missing, null, or blank. */
    private void ensureField(ObjectNode root, String path, String defaultValue) {
        try {
            JsonNode current = getNodeAtPath(root, path);
            if (current == null || current.isNull() || (current.isTextual() && current.asText().isBlank())) {
                setValueAtPath(root, path, objectMapper.valueToTree(defaultValue));
                log.debug("ensureField: set '{}' = '{}'", path, defaultValue);
            }
        } catch (Exception e) {
            log.warn("ensureField: could not set '{}': {}", path, e.getMessage());
        }
    }

    private void autoFillOwnerReferenceIds(ObjectNode payload, CampaignSession session, String adAccountId) {
        JsonNode adAccountIdNode = objectMapper.valueToTree(adAccountId);

        // Top-level campaign ownerReferenceId
        autoFillBlankField(payload, session, "ownerReferenceId", adAccountIdNode);

        // Per-ad: ownerReferenceId + ensure creativeRequest.type is always present
        ArrayNode adSets = (ArrayNode) payload.get("adSets");
        for (int i = 0; i < adSets.size(); i++) {
            ArrayNode ads = (ArrayNode) adSets.get(i).get("ads");
            for (int j = 0; j < ads.size(); j++) {
                String ownerPath = "adSets[" + i + "].ads[" + j + "].creativeRequest.ownerReferenceId";
                autoFillBlankField(payload, session, ownerPath, adAccountIdNode);

                // creativeRequest.type is handled by autoFillRequiredFields()
            }
        }
    }

    private void autoFillBlankField(ObjectNode payload, CampaignSession session,
                                    String path, JsonNode value) {
        if (session.getUserValues().containsKey(path)) return;
        try {
            JsonNode current = getNodeAtPath(payload, path);
            if (current == null || current.isNull() || (current.isTextual() && current.asText().isBlank())) {
                setValueAtPath(payload, path, value);
                log.debug("Auto-filled '{}' with '{}'", path, value);
            }
        } catch (Exception e) {
            log.warn("Could not auto-fill field for path '{}': {}", path, e.getMessage());
        }
    }

    private void autoFillNameField(ObjectNode payload, CampaignSession session,
                                   String path, String generated) {
        if (session.getUserValues().containsKey(path)) return;
        try {
            JsonNode current = getNodeAtPath(payload, path);
            if (current == null || current.isNull() || (current.isTextual() && current.asText().isBlank())) {
                setValueAtPath(payload, path, objectMapper.valueToTree(generated));
                log.debug("Auto-filled '{}' with '{}'", path, generated);
            }
        } catch (Exception e) {
            log.warn("Could not auto-fill name for path '{}': {}", path, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Path traversal helpers
    // -------------------------------------------------------------------------

    private void setValueAtPath(ObjectNode root, String path, JsonNode value) {
        List<Object> segments = parsePath(path);
        JsonNode current = root;

        for (int i = 0; i < segments.size() - 1; i++) {
            Object seg = segments.get(i);
            if (seg instanceof Integer idx) {
                current = ((ArrayNode) current).get(idx);
            } else {
                current = current.path((String) seg);
            }
            if (current == null || current.isMissingNode()) {
                throw new IllegalArgumentException("Path segment not found: " + seg + " in path: " + path);
            }
        }

        Object lastSeg = segments.get(segments.size() - 1);
        if (!(lastSeg instanceof String key)) {
            throw new IllegalArgumentException("Last path segment must be a string key: " + path);
        }
        ((ObjectNode) current).set(key, value);
    }

    private JsonNode getNodeAtPath(JsonNode root, String path) {
        List<Object> segments = parsePath(path);
        JsonNode current = root;
        for (Object seg : segments) {
            if (seg instanceof Integer idx) {
                current = current.path(idx);
            } else {
                current = current.path((String) seg);
            }
            if (current == null || current.isMissingNode()) return null;
        }
        return current;
    }

    private List<Object> parsePath(String path) {
        List<Object> segments = new ArrayList<>();
        for (String part : path.split("\\.")) {
            if (part.contains("[")) {
                String name = part.substring(0, part.indexOf('['));
                int idx = Integer.parseInt(part.substring(part.indexOf('[') + 1, part.indexOf(']')));
                if (!name.isBlank()) segments.add(name);
                segments.add(idx);
            } else {
                segments.add(part);
            }
        }
        return segments;
    }
}
