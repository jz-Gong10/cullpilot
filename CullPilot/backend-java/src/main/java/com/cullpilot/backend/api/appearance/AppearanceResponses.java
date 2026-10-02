package com.cullpilot.backend.api.appearance;

import com.cullpilot.backend.service.appearance.AppearanceCatalog;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AppearanceResponses {
    private AppearanceResponses() {}

    public record Combination(@JsonProperty("color_id") String colorId,
                              @JsonProperty("style_id") String styleId) {}

    public record Onboarding(
            @JsonProperty("questionnaire_version") String questionnaireVersion,
            List<AppearanceCatalog.Question> questions,
            List<AppearanceCatalog.ThemeColor> colors,
            List<AppearanceCatalog.Style> styles,
            @JsonProperty("onboarding_required") boolean onboardingRequired) {}

    public record Recommendation(
            Combination recommended,
            List<Combination> alternatives,
            @JsonProperty("score_detail") Map<String, Object> scoreDetail,
            @JsonProperty("questionnaire_version") String questionnaireVersion,
            boolean applied) {}

    public record CurrentAppearance(
            @JsonProperty("color_id") String colorId,
            @JsonProperty("style_id") String styleId,
            String source,
            @JsonProperty("questionnaire_version") String questionnaireVersion,
            @JsonProperty("onboarding_status") String onboardingStatus,
            @JsonProperty("onboarding_required") boolean onboardingRequired,
            @JsonProperty("updated_at") Instant updatedAt) {}
}
