package com.cullpilot.backend.integration;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AnalysisContract {
    private AnalysisContract() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(String projectId, List<InputAsset> assets, Strategy strategy,
                          boolean rebuildGroups, List<ExistingGroup> existingGroups) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InputAsset(String assetId, String filePath) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExistingGroup(String id, List<String> assetIds) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Strategy(int keepPerGroup, String strictness, String contentMode,
                           Map<String, Double> weights, Map<String, Boolean> constraints) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(String projectId, List<GroupResult> groups, List<AssetResult> assets,
                         List<String> warnings, Map<String, String> algorithm) {
        public Result(String projectId, List<GroupResult> groups, List<AssetResult> assets,
                      List<String> warnings) {
            this(projectId, groups, assets, warnings, Map.of());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupResult(String id, String groupType, boolean hasFace, List<String> assetIds,
                              double confidence, double averageSimilarity, double maxDistance,
                              List<String> reasons, List<String> recommendedAssetIds,
                              Instant timeFrom, Instant timeTo) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AssetResult(String assetId, String groupId, int rank, String recommendation,
                              double recommendScore, List<String> recommendReasons,
                              String membershipReason, Map<String, Object> quality,
                              Map<String, Object> features, Map<String, Object> exif) {}
}
