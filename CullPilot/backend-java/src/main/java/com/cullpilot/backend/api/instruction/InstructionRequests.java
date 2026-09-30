package com.cullpilot.backend.api.instruction;

import jakarta.validation.constraints.Size;

import java.util.List;

public final class InstructionRequests {
    private InstructionRequests() {}

    public record ParseInstructionRequest(
            @Size(max = 2000, message = "筛选要求不能超过 2000 个字符")
            String text,
            List<String> allowedFeatures) {
    }
}
