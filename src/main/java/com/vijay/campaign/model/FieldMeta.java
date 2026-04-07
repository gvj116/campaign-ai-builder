package com.vijay.campaign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldMeta {
    private String path;
    private String label;
    private boolean askUser;
    private List<OptionMeta> options;
}
