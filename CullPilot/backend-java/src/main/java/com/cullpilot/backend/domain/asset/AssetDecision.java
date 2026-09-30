package com.cullpilot.backend.domain.asset;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum AssetDecision {
    KEEP,
    REVIEW,
    REJECT;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
