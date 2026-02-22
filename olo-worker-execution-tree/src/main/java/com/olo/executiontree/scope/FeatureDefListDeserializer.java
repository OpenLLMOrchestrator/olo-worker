package com.olo.executiontree.scope;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deserializes scope "features" as either an array of strings (e.g. ["debug"]) or an array of objects (e.g. [{"id":"debug","displayName":"Debug"}]).
 */
public final class FeatureDefListDeserializer extends JsonDeserializer<List<FeatureDef>> {

    @Override
    public List<FeatureDef> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<FeatureDef> result = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.isTextual()) {
                result.add(new FeatureDef(element.asText(), null, null));
            } else if (element.isObject()) {
                String id = element.has("id") ? element.get("id").asText(null) : null;
                String displayName = element.has("displayName") ? element.get("displayName").asText(null) : null;
                String contractVersion = element.has("contractVersion") ? element.get("contractVersion").asText(null) : null;
                result.add(new FeatureDef(id, displayName, contractVersion));
            }
        }
        return List.copyOf(result);
    }
}
