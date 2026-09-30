package com.cullpilot.backend.domain.asset;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum AssetRecommendation {
    KEEP,
    REVIEW,
    REJECT;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
