package com.cullpilot.backend.api.job;

import com.cullpilot.backend.domain.job.Job;
import com.cullpilot.backend.domain.job.JobError;
import com.cullpilot.backend.domain.job.JobStatus;
import com.cullpilot.backend.domain.job.JobType;
import com.cullpilot.backend.domain.project.ProjectStatus;

import java.time.Instant;
import java.util.List;

public final class JobResponses {

    private JobResponses() {
    }

    public record JobResponse(
            String id,
            JobType type,
            JobStatus status,
            int progress,
            String currentStage,
            int processedCount,
            int totalCount,
            int errorCount,
            String errorMessage,
            Instant createdAt,
            Instant finishedAt,
            List<JobErrorResponse> errors) {

        public static JobResponse from(Job job, List<JobError> errors) {
            return new JobResponse(
                    job.getId(),
                    job.getType(),
                    job.getStatus(),
                    job.getProgress(),
                    job.getCurrentStage(),
                    job.getProcessedCount(),
                    job.getTotalCount(),
                    job.getErrorCount(),
                    job.getErrorMessage(),
                    job.getCreatedAt(),
                    job.getFinishedAt(),
                    errors.stream().map(JobErrorResponse::from).toList());
        }
    }

    public record JobErrorResponse(
            String assetId,
            String stage,
            String code,
            String message,
            Instant createdAt) {

        public static JobErrorResponse from(JobError error) {
            return new JobErrorResponse(
                    error.getAssetId(),
                    error.getStage(),
                    error.getCode(),
                    error.getMessage(),
                    error.getCreatedAt());
        }
    }

    public record ProjectSummaryResponse(
            String projectId,
            ProjectStatus status,
            long assetCount,
            long analyzedCount,
            long groupCount,
            DecisionCounts decisionCounts,
            DecisionCounts recommendationCounts,
            String activeJobId,
            List<String> warnings) {
    }

    public record DecisionCounts(long keep, long review, long reject) {
    }
}
