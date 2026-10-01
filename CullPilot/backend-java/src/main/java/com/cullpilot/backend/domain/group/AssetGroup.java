package com.cullpilot.backend.domain.group;

import com.cullpilot.backend.analysis.JsonColumns;
import com.cullpilot.backend.integration.AnalysisContract.GroupResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "asset_groups")
public class AssetGroup {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;
    @Column(name = "group_no", nullable = false)
    private int groupNo;
    @Column(name = "group_type", nullable = false, length = 40)
    private String groupType;
    @Column(name = "has_face", nullable = false)
    private boolean hasFace;
    @Column(name = "asset_count", nullable = false)
    private int assetCount;
    @Column(nullable = false)
    private double confidence;
    @Column(name = "average_similarity", nullable = false)
    private double averageSimilarity;
    @Column(name = "max_distance", nullable = false)
    private double maxDistance;
    @Column(name = "reasons_json", nullable = false, columnDefinition = "TEXT")
    private String reasonsJson;
    @Column(name = "recommended_asset_ids_json", nullable = false, columnDefinition = "TEXT")
    private String recommendedAssetIdsJson;
    @Column(name = "time_from")
    private Instant timeFrom;
    @Column(name = "time_to")
    private Instant timeTo;

    protected AssetGroup() {}

    public AssetGroup(String projectId, int groupNo, GroupResult result) {
        this.id = result.id();
        this.projectId = projectId;
        this.groupNo = groupNo;
        this.groupType = result.groupType();
        this.hasFace = result.hasFace();
        this.assetCount = result.assetIds().size();
        this.confidence = result.confidence();
        this.averageSimilarity = result.averageSimilarity();
        this.maxDistance = result.maxDistance();
        this.reasonsJson = JsonColumns.write(result.reasons());
        this.recommendedAssetIdsJson = JsonColumns.write(result.recommendedAssetIds());
        this.timeFrom = result.timeFrom();
        this.timeTo = result.timeTo();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getGroupNo() { return groupNo; }
    public String getGroupType() { return groupType; }
    public boolean isHasFace() { return hasFace; }
    public int getAssetCount() { return assetCount; }
    public double getConfidence() { return confidence; }
    public double getAverageSimilarity() { return averageSimilarity; }
    public double getMaxDistance() { return maxDistance; }
    public List<String> getReasons() { return JsonColumns.strings(reasonsJson); }
    public List<String> getRecommendedAssetIds() { return JsonColumns.strings(recommendedAssetIdsJson); }
    public Instant getTimeFrom() { return timeFrom; }
    public Instant getTimeTo() { return timeTo; }

    public void removeAsset(String assetId, int remainingCount) {
        assetCount = remainingCount;
        List<String> recommended = new ArrayList<>(getRecommendedAssetIds());
        recommended.removeIf(assetId::equals);
        recommendedAssetIdsJson = JsonColumns.write(recommended);
    }
}
