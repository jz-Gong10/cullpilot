package com.cullpilot.backend.api.export;

import com.cullpilot.backend.domain.export.ExportTask;

import java.time.Instant;

public final class ExportResponses {
    private ExportResponses() {}

    public record ExportResponse(String id, String projectId, String status, String selection,
                                 int progress, int processedCount, int totalCount,
                                 String errorMessage, String downloadUrl,
                                 Instant createdAt, Instant finishedAt) {
        public static ExportResponse from(ExportTask task) {
            int progress = task.getStatus() == ExportTask.Status.SUCCEEDED ? 100
                    : task.getTotalCount() == 0 ? 0
                    : (int) ((long) task.getProcessedCount() * 100 / task.getTotalCount());
            return new ExportResponse(task.getId(), task.getProjectId(),
                    task.getStatus().name().toLowerCase(),
                    task.getSelection() == ExportTask.Selection.KEEP ? "keep" : "keepAndReview",
                    progress, task.getProcessedCount(), task.getTotalCount(),
                    task.getErrorMessage(),
                    task.getStatus() == ExportTask.Status.SUCCEEDED
                            ? "/api/v1/exports/" + task.getId() + "/download" : null,
                    task.getCreatedAt(), task.getFinishedAt());
        }
    }
}
