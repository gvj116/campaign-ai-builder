package com.hotstar.campaign.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotstar.campaign.config.CampaignApiProperties;
import com.hotstar.campaign.exception.LegoWorkflowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CampaignApiClient {

    private static final Logger log = LoggerFactory.getLogger(CampaignApiClient.class);

    private final RestClient restClient;
    private final CampaignApiProperties properties;
    private final ObjectMapper objectMapper;

    public CampaignApiClient(
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            CampaignApiProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilderProvider.getObject()
                .baseUrl(properties.getLego().getBaseUrl())
                .build();
    }

    public JsonNode postWorkflow(
            String businessAccountId,
            String adAccountId,
            JsonNode opsCampaignRequest,
            String authorizationHeaderValue) {

        String path = properties.getLego().getApiPrefix()
                + "/businessAccounts/" + businessAccountId
                + "/adAccounts/" + adAccountId
                + "/managed-ops-workflows";

        byte[] bodyBytes;
        try {
            log.info("Writing value as bytes: {}", stripNullIdempotencyKeys(opsCampaignRequest));
            bodyBytes = objectMapper.writeValueAsBytes(stripNullIdempotencyKeys(opsCampaignRequest));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize workflow request", e);
        }

        try {
            return restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(path).build())
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeaderValue)
                    .header("request_from", "managed-ops")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyBytes)
                    .exchange((request, response) -> {
                        byte[] buf = response.getBody().readAllBytes();
                        if (response.getStatusCode().isError()) {
                            throw new LegoWorkflowException(
                                    "Lego managed-ops-workflows failed: HTTP " + response.getStatusCode(),
                                    response.getStatusCode().value(),
                                    new String(buf));
                        }
                        return objectMapper.readTree(buf);
                    });
        } catch (LegoWorkflowException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Lego workflow call failed", e);
        }
    }

    /** Remove explicit null idempotency keys so server can assign UUIDs (matches typical UI payloads). */
    private JsonNode stripNullIdempotencyKeys(JsonNode root) {
        if (!(root instanceof ObjectNode obj)) return root;

        ObjectNode copy = obj.deepCopy();
        if (copy.has("idempotencyKey") && copy.get("idempotencyKey").isNull()) {
            copy.remove("idempotencyKey");
        }
        if (copy.has("adSets") && copy.get("adSets").isArray()) {
            for (int i = 0; i < copy.get("adSets").size(); i++) {
                JsonNode adSet = copy.get("adSets").get(i);
                if (adSet instanceof ObjectNode aso) {
                    if (aso.has("idempotencyKey") && aso.get("idempotencyKey").isNull()) {
                        aso.remove("idempotencyKey");
                    }
                    if (aso.has("ads") && aso.get("ads").isArray()) {
                        for (int j = 0; j < aso.get("ads").size(); j++) {
                            JsonNode ad = aso.get("ads").get(j);
                            if (ad instanceof ObjectNode ado
                                    && ado.has("idempotencyKey")
                                    && ado.get("idempotencyKey").isNull()) {
                                ado.remove("idempotencyKey");
                            }
                        }
                    }
                }
            }
        }
        return copy;
    }

    public static String extractCampaignId(JsonNode compositeResponse) {
        if (compositeResponse == null) return null;
        JsonNode draft = compositeResponse.get("draftResponse");
        if (draft != null && draft.hasNonNull("id")) return draft.get("id").asText();
        JsonNode reg = compositeResponse.get("registryResponse");
        if (reg != null && reg.hasNonNull("id")) return reg.get("id").asText();
        return null;
    }
}
