package com.cullpilot.backend.domain.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "job_errors")
public class JobError {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "job_id", length = 36, nullable = false, updatable = false)
    private String jobId;

    @Column(name = "asset_id", length = 36)
    private String assetId;

    @Column(nullable = false, length = 30)
    private String stage;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JobError() {
    }

    public JobError(String id, String jobId, String assetId, String stage, String code, String message, Instant now) {
        this.id = id;
        this.jobId = jobId;
        this.assetId = assetId;
        this.stage = stage;
        this.code = code;
        this.message = message;
        this.createdAt = now;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public String getJobId() { return jobId; }
    public String getAssetId() { return assetId; }
    public String getStage() { return stage; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
