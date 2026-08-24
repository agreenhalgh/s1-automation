package com.newbrid.epr_automation.gateway.route;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Decodes the {@code outputsJson} column the control plane stores a run's outputs in.
 *
 * <p>Failure returns an empty map rather than propagating. This runs at the very end of a
 * successful request, and the alternative is telling a caller their run failed — after it
 * succeeded, and after they waited for it — because a column would not parse. The outputs are
 * the least important part of that answer; the outcome is the point.
 */
final class OutputsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private OutputsCodec() {}

    static Map<String, String> decode(String outputsJson) {
        if (outputsJson == null || outputsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> outputs = MAPPER.readValue(outputsJson, STRING_MAP);
            return outputs == null ? Map.of() : outputs;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
