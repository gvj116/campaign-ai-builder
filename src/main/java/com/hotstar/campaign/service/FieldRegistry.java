package com.hotstar.campaign.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotstar.campaign.model.FieldMeta;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FieldRegistry {

    private final ObjectMapper objectMapper;
    private List<FieldMeta> fields;

    @PostConstruct
    public void load() {
        try {
            var resource = new ClassPathResource("campaign-fields.json");
            fields = objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {});
            log.info("Loaded {} campaign fields from campaign-fields.json", fields.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load campaign-fields.json", e);
        }
    }

    public List<FieldMeta> getAskableFields() {
        return fields.stream().filter(FieldMeta::isAskUser).toList();
    }
}
