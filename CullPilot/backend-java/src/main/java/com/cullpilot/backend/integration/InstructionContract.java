package com.cullpilot.backend.integration;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

public final class InstructionContract {
    private InstructionContract() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(String text, List<String> allowedFeatures) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Response(Strategy strategy, double confidence, boolean fallbackUsed,
                           String provider, String model) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Strategy(int keepPerGroup, String strictness, String contentMode,
                           Map<String, Double> weights, Map<String, Boolean> constraints,
                           List<String> unsupportedTerms, String explanation) {}
}
