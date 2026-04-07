package com.vijay.campaign.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        // No default advisor here — MessageChatMemoryAdvisor is created per-call
        // with the session-specific conversationId in CampaignChatService
        return builder.build();
    }
}
