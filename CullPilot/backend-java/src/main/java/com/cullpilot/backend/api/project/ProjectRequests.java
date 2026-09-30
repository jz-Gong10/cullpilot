package com.cullpilot.backend.api.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public final class ProjectRequests {

    private ProjectRequests() {
    }

    public record CreateProjectRequest(
            @NotBlank(message = "项目名称不能为空")
            @Size(max = 100, message = "项目名称不能超过 100 个字符")
            String name,
            @Valid ProjectSettingsPatch settings) {
    }

    public record ProjectSettingsPatch(
            @Min(value = 1, message = "keepPerGroup 不能小于 1")
            @Max(value = 10, message = "keepPerGroup 不能大于 10")
            Integer keepPerGroup,
            String strictness,
            String contentMode,
            Map<String, Double> weights,
            Map<String, Boolean> constraints,
            @Valid PrivacySettingsPatch privacy) {
    }

    public record PrivacySettingsPatch(
            Boolean sendThumbnailsToProvider,
            Boolean stripGpsOnExport) {
    }
}
