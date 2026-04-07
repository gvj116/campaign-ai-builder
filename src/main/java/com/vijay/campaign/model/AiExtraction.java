package com.vijay.campaign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/**
 * The structured JSON Claude returns on every turn.
 *
 * During COLLECTING:
 *   { "reply": "...", "updates": {"fieldPath": value, ...}, "readyToSubmit": false }
 *
 * During CONFIRMING:
 *   { "reply": "...", "action": "CONFIRM" | "MODIFY", "updates": {...} }
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiExtraction {
    private String reply;
    private Map<String, Object> updates;
    private boolean readyToSubmit;
    private String action; // "CONFIRM" or "MODIFY" — used in CONFIRMING state
}
