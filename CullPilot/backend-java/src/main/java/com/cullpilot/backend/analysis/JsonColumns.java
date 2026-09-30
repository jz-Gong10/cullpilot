package com.cullpilot.backend.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class JsonColumns {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonColumns() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Analysis metadata is not JSON serializable", exception);
        }
    }

    public static Map<String, Object> object(String value) {
        if (value == null) return Map.of();
        try {
            return MAPPER.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored analysis metadata is invalid", exception);
        }
    }

    public static List<String> strings(String value) {
        if (value == null) return List.of();
        try {
            return MAPPER.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored analysis metadata is invalid", exception);
        }
    }
}
