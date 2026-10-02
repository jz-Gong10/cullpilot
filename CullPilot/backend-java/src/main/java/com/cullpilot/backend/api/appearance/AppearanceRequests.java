package com.cullpilot.backend.api.appearance;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public final class AppearanceRequests {
    private AppearanceRequests() {}

    public record SubmitOnboarding(
            @NotBlank @JsonProperty("questionnaire_version") String questionnaireVersion,
            @NotNull Map<String, String> answers,
            @NotNull Boolean apply,
            boolean restart) {}

    public record UpdateAppearance(
            @NotBlank @JsonProperty("color_id") String colorId,
            @NotBlank @JsonProperty("style_id") String styleId) {}
}
