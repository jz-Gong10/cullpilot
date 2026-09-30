package com.cullpilot.backend.domain.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum UserStatus {
    ACTIVE,
    DISABLED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static UserStatus fromValue(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
