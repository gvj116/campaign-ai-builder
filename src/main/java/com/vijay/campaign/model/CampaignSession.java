package com.vijay.campaign.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class CampaignSession {

    private final String sessionId;
    private State state = State.COLLECTING;

    // Set from first request, reused for the API call
    private String businessAccountId;
    private String adAccountId;
    private String brandId;
    private String authorizationHeader; // forwarded as-is from incoming request

    /**
     * Values explicitly provided by the user.
     * Keys are field paths from campaign-fields.json (e.g. "name", "adSets[0].name").
     */
    private final Map<String, Object> userValues = new LinkedHashMap<>();

    public enum State {
        COLLECTING,   // gathering info from user
        CONFIRMING,   // showing summary, awaiting confirmation
        SUBMITTED     // API call made
    }
}
