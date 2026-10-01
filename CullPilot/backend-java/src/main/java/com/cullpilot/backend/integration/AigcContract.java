package com.cullpilot.backend.integration;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

public final class AigcContract {
    private AigcContract() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(String sourcePath, String prompt, String model, String size,
                          boolean promptExtend, boolean watermark) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(String imageBase64, String mimeType, String promptUsed,
                         String provider, String model) {}
}
