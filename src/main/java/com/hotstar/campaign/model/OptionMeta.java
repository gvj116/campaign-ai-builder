package com.hotstar.campaign.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import lombok.Data;

import java.io.IOException;

/**
 * Represents a field option with a human-friendly label and the exact API enum value.
 *
 * Supports two formats in campaign-fields.json:
 *   Plain string:  "PROMO"
 *     → label="PROMO", value="PROMO"
 *   Object:        {"label": "Promo", "value": "HOTSTAR_PROMO"}
 *     → label="Promo", value="HOTSTAR_PROMO"
 */
@Data
@JsonDeserialize(using = OptionMeta.Deserializer.class)
public class OptionMeta {

    private String label;  // UI display name (e.g. "Promo", "Open Exchange")
    private String value;  // Exact API enum value (e.g. "HOTSTAR_PROMO", "OE")

    /** Returns the display label, falling back to value if label is not set. */
    public String getDisplayLabel() {
        return (label != null && !label.isBlank()) ? label : value;
    }

    static class Deserializer extends StdDeserializer<OptionMeta> {
        Deserializer() { super(OptionMeta.class); }

        @Override
        public OptionMeta deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            OptionMeta o = new OptionMeta();
            if (node.isTextual()) {
                // Plain string: "PROMO" → label = value = "PROMO"
                o.value = node.asText();
                o.label = node.asText();
            } else {
                // Object: {"label": "Promo", "value": "HOTSTAR_PROMO"}
                o.value = node.path("value").asText();
                o.label = node.has("label") ? node.path("label").asText() : o.value;
            }
            return o;
        }
    }
}
