package com.cullpilot.backend.domain.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum Strictness {
    LOOSE,
    STANDARD,
    STRICT;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Strictness fromValue(String value) {
        if (value == null) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
