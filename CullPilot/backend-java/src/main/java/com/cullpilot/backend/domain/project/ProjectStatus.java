package com.cullpilot.backend.domain.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum ProjectStatus {
    CREATED,
    UPLOADING,
    ANALYZING,
    READY,
    FAILED,
    DELETING;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ProjectStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
