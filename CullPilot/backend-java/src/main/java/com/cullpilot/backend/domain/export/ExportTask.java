package com.cullpilot.backend.domain.export;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "exports")
public class ExportTask {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }
    public enum Selection { KEEP, KEEP_AND_REVIEW }

    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Selection selection;
    @Column(name = "copy_images", nullable = false)
    private boolean copyImages;
    @Column(name = "strip_gps", nullable = false)
    private boolean stripGps;
    @Column(name = "include_manifest", nullable = false)
    private boolean includeManifest;
    @Column(name = "processed_count", nullable = false)
    private int processedCount;
    @Column(name = "total_count", nullable = false)
    private int totalCount;
    @Column(name = "file_path", length = 500)
    private String filePath;
    @Column(name = "error_message", length = 500)
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Version
    @Column(name = "entity_version", nullable = false)
    private long version;

    protected ExportTask() {}

    public ExportTask(String id, String projectId, Selection selection, boolean copyImages,
                      boolean stripGps, boolean includeManifest) {
        this.id = id;
        this.projectId = projectId;
        this.selection = selection;
        this.copyImages = copyImages;
        this.stripGps = stripGps;
        this.includeManifest = includeManifest;
        this.status = Status.QUEUED;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public Status getStatus() { return status; }
    public Selection getSelection() { return selection; }
    public boolean isCopyImages() { return copyImages; }
    public boolean isStripGps() { return stripGps; }
    public boolean isIncludeManifest() { return includeManifest; }
    public int getProcessedCount() { return processedCount; }
    public int getTotalCount() { return totalCount; }
    public String getFilePath() { return filePath; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setStatus(Status status) { this.status = status; }
    public void setProcessedCount(int processedCount) { this.processedCount = processedCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
