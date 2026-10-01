package com.cullpilot.backend.api.aigc;

import com.cullpilot.backend.domain.aigc.AigcEdit;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class AigcResponses {
    private AigcResponses() {}

    public record EditResponse(
            String id,
            String projectId,
            String assetId,
            String groupId,
            String status,
            String prompt,
            String promptUsed,
            String model,
            String provider,
            String imageUrl,
            String originalUrl,
            String mimeType,
            Long sizeBytes,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) {

        public static EditResponse from(AigcEdit edit) {
            return new EditResponse(edit.getId(), edit.getProjectId(), edit.getAssetId(),
                    "aigc-" + edit.getProjectId(), edit.getStatus().name().toLowerCase(Locale.ROOT),
                    edit.getPrompt(), edit.getPromptUsed(), edit.getModel(), edit.getProvider(),
                    edit.getGeneratedPath() == null ? null : "/api/v1/aigc-edits/" + edit.getId() + "/image",
                    "/api/v1/assets/" + edit.getAssetId() + "/original", edit.getMimeType(),
                    edit.getSizeBytes(), edit.getErrorMessage(), edit.getCreatedAt(),
                    edit.getStartedAt(), edit.getFinishedAt());
        }
    }

    public record BatchResponse(String groupId, List<EditResponse> items) {}
    public record GroupResponse(String id, String projectId, String groupType,
                                int imageCount, List<EditResponse> items) {}
}
