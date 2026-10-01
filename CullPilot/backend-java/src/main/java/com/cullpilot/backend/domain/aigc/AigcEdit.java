package com.cullpilot.backend.domain.aigc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "aigc_edits")
public class AigcEdit {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "asset_id", length = 36, nullable = false, updatable = false)
    private String assetId;

    @Column(name = "prompt_text", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "prompt_used", columnDefinition = "TEXT")
    private String promptUsed;

    @Column(length = 100, nullable = false)
    private String model;

    @Column(name = "image_size", length = 20, nullable = false)
    private String size;

    @Column(name = "prompt_extend", nullable = false)
    private boolean promptExtend;

    @Column(nullable = false)
    private boolean watermark;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AigcEditStatus status;

    @Column(length = 50)
    private String provider;

    @Column(name = "generated_path", length = 500)
    private String generatedPath;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    @Column(name = "entity_version", nullable = false)
    private long version;

    protected AigcEdit() {
    }

    public AigcEdit(String id, String projectId, String assetId, String prompt,
                    String model, String size, boolean promptExtend, boolean watermark,
                    Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.assetId = assetId;
        this.prompt = prompt;
        this.model = model;
        this.size = size;
        this.promptExtend = promptExtend;
        this.watermark = watermark;
        this.status = AigcEditStatus.QUEUED;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getAssetId() { return assetId; }
    public String getPrompt() { return prompt; }
    public String getPromptUsed() { return promptUsed; }
    public String getModel() { return model; }
    public String getSize() { return size; }
    public boolean isPromptExtend() { return promptExtend; }
    public boolean isWatermark() { return watermark; }
    public AigcEditStatus getStatus() { return status; }
    public String getProvider() { return provider; }
    public String getGeneratedPath() { return generatedPath; }
    public String getMimeType() { return mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getVersion() { return version; }

    public void markRunning(Instant now) {
        this.status = AigcEditStatus.RUNNING;
        this.startedAt = now;
        this.errorMessage = null;
    }

    public void markSucceeded(String promptUsed, String provider, String model,
                              String generatedPath, String mimeType, long sizeBytes, Instant now) {
        this.status = AigcEditStatus.SUCCEEDED;
        this.promptUsed = promptUsed;
        this.provider = provider;
        this.model = model;
        this.generatedPath = generatedPath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.finishedAt = now;
        this.errorMessage = null;
    }

    public void markFailed(String message, Instant now) {
        this.status = AigcEditStatus.FAILED;
        this.errorMessage = message;
        this.finishedAt = now;
    }
}
