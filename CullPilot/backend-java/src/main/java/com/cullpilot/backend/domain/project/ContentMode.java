package com.cullpilot.backend.domain.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum ContentMode {
    AUTO,
    PORTRAIT,
    LANDSCAPE,
    MIXED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ContentMode fromValue(String value) {
        if (value == null) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
