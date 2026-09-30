package com.cullpilot.backend.api.instruction;

import java.util.List;
import java.util.Map;

public final class InstructionResponses {
    private InstructionResponses() {}

    public record ParseInstructionResponse(
            String text,
            SelectionStrategy strategy,
            double confidence,
            List<String> unsupportedTerms,
            String explanation,
            boolean fallbackUsed,
            String provider,
            String model) {
    }

    public record SelectionStrategy(
            int keepPerGroup,
            String strictness,
            String contentMode,
            Map<String, Double> weights,
            Map<String, Boolean> constraints) {
    }
}
