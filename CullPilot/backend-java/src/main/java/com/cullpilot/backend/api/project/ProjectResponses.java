package com.cullpilot.backend.api.project;

import com.cullpilot.backend.domain.project.ProjectSettings;
import com.cullpilot.backend.domain.project.ProjectStatus;

import java.time.Instant;
import java.util.List;

public final class ProjectResponses {

    private ProjectResponses() {
    }

    public record ProjectResponse(
            String id,
            String name,
            ProjectStatus status,
            ProjectSettings settings,
            long assetCount,
            long groupCount,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PageResponse<T>(
            List<T> items,
            int page,
            int pageSize,
            long total) {
    }

    public record CleanupJobResponse(
            String id,
            String type,
            String status,
            Instant createdAt,
            Instant finishedAt) {
    }

    public record DeleteProjectResponse(
            String projectId,
            CleanupJobResponse job) {
    }
}
