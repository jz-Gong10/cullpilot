package com.cullpilot.backend.domain.asset;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum AnalysisStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
