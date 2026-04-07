package com.hotstar.campaign.model;

public record ChatRequest(
        String sessionId,
        String message,
        String businessAccountId,
        String adAccountId,
        String brandId
) {}
