package com.cullpilot.backend.domain.job;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum JobType {
    ANALYSIS;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static JobType fromValue(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
