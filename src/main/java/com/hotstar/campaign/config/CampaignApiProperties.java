package com.hotstar.campaign.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "campaign.api")
public class CampaignApiProperties {

    private Lego lego = new Lego();

    @Data
    public static class Lego {
        private String baseUrl;
        private String apiPrefix = "/api/v2";
    }
}
