package com.cullpilot.backend.domain.job;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL_FAILED,
    FAILED,
    CANCELLED;

    @JsonValue
    public String value() {
        return this == PARTIAL_FAILED ? "partialFailed" : name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static JobStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.equalsIgnoreCase("partialFailed")) {
            return PARTIAL_FAILED;
        }
        return valueOf(normalized.toUpperCase(Locale.ROOT));
    }
}
