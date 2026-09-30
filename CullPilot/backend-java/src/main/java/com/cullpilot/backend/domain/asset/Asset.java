package com.cullpilot.backend.domain.asset;

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
@Table(name = "assets")
public class Asset {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "original_path", nullable = false, length = 500)
    private String originalPath;

    @Column(name = "thumbnail_path", nullable = false, length = 500)
    private String thumbnailPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 20)
    private AnalysisStatus analysisStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssetDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AssetRecommendation recommendation;

    @Column(name = "group_id", length = 36)
    private String groupId;

    @Column(name = "group_rank")
    private Integer rank;

    @Column(name = "recommend_score")
    private Double recommendScore;

    @Column(name = "recommend_reasons_json", columnDefinition = "TEXT")
    private String recommendReasonsJson;

    @Column(name = "membership_reason", length = 500)
    private String membershipReason;

    @Column(name = "quality_json", columnDefinition = "TEXT")
    private String qualityJson;

    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;

    @Column(name = "exif_json", columnDefinition = "TEXT")
    private String exifJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "entity_version", nullable = false)
    private long version;

    protected Asset() {
    }

    public Asset(
            String id,
            String projectId,
            String originalName,
            String mimeType,
            long sizeBytes,
            int width,
            int height,
            String sha256,
            String originalPath,
            String thumbnailPath,
            Instant now) {
        this.id = id;
        this.projectId = projectId;
        this.originalName = originalName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.sha256 = sha256;
        this.originalPath = originalPath;
        this.thumbnailPath = thumbnailPath;
        this.analysisStatus = AnalysisStatus.PENDING;
        this.decision = AssetDecision.REVIEW;
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
    public String getOriginalName() { return originalName; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getSha256() { return sha256; }
    public String getOriginalPath() { return originalPath; }
    public String getThumbnailPath() { return thumbnailPath; }
    public AnalysisStatus getAnalysisStatus() { return analysisStatus; }
    public AssetDecision getDecision() { return decision; }
    public AssetRecommendation getRecommendation() { return recommendation; }
    public String getGroupId() { return groupId; }
    public Integer getRank() { return rank; }
    public Double getRecommendScore() { return recommendScore; }
    public String getRecommendReasonsJson() { return recommendReasonsJson; }
    public String getMembershipReason() { return membershipReason; }
    public String getQualityJson() { return qualityJson; }
    public String getFeaturesJson() { return featuresJson; }
    public String getExifJson() { return exifJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void setDecision(AssetDecision decision) {
        this.decision = decision;
    }

    public void setRecommendation(AssetRecommendation recommendation) {
        this.recommendation = recommendation;
    }

    public void setAnalysisStatus(AnalysisStatus analysisStatus) {
        this.analysisStatus = analysisStatus;
    }

    public void setAnalysisResult(String groupId, Integer rank, AssetRecommendation recommendation,
                                  double score, String reasonsJson, String membershipReason,
                                  String qualityJson, String featuresJson, String exifJson) {
        this.groupId = groupId;
        this.rank = rank;
        this.recommendation = recommendation;
        this.recommendScore = score;
        this.recommendReasonsJson = reasonsJson;
        this.membershipReason = membershipReason;
        this.qualityJson = qualityJson;
        this.featuresJson = featuresJson;
        this.exifJson = exifJson;
        this.analysisStatus = AnalysisStatus.COMPLETED;
    }
}
