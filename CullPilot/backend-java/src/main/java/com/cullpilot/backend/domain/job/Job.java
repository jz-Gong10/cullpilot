package com.cullpilot.backend.domain.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private JobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobStatus status;

    @Column(nullable = false)
    private int progress;

    @Column(name = "current_stage", length = 30)
    private String currentStage;

    @Column(name = "processed_count", nullable = false)
    private int processedCount;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(nullable = false)
    private boolean force;

    @Column(name = "rebuild_groups", nullable = false)
    private boolean rebuildGroups;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "entity_version", nullable = false)
    private long version;

    protected Job() {
    }

    public Job(
            String id,
            String projectId,
            JobType type,
            int totalCount,
            String idempotencyKey,
            boolean force,
            boolean rebuildGroups,
            Instant now) {
        this.id = id;
        this.projectId = projectId;
        this.type = type;
        this.status = JobStatus.QUEUED;
        this.progress = 0;
        this.currentStage = "reading";
        this.processedCount = 0;
        this.totalCount = totalCount;
        this.errorCount = 0;
        this.idempotencyKey = idempotencyKey;
        this.force = force;
        this.rebuildGroups = rebuildGroups;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public JobType getType() { return type; }
    public JobStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getCurrentStage() { return currentStage; }
    public int getProcessedCount() { return processedCount; }
    public int getTotalCount() { return totalCount; }
    public int getErrorCount() { return errorCount; }
    public String getErrorMessage() { return errorMessage; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public boolean isForce() { return force; }
    public boolean isRebuildGroups() { return rebuildGroups; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getVersion() { return version; }

    public void setStatus(JobStatus status) { this.status = status; }
    public void setProgress(int progress) { this.progress = Math.max(0, Math.min(100, progress)); }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public void setProcessedCount(int processedCount) { this.processedCount = processedCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
