package com.cullpilot.backend.api.group;

import com.cullpilot.backend.domain.group.AssetGroup;

import java.time.Instant;
import java.util.List;

public final class GroupResponses {
    private GroupResponses() {}

    public record GroupResponse(String id, String projectId, int groupNo, String groupType,
                                boolean hasFace, int assetCount, double confidence,
                                double averageSimilarity, double maxDistance,
                                TimeRange timeRange, List<String> reasons,
                                List<String> recommendedAssetIds) {
        public static GroupResponse from(AssetGroup group) {
            return new GroupResponse(group.getId(), group.getProjectId(), group.getGroupNo(),
                    group.getGroupType(), group.isHasFace(), group.getAssetCount(),
                    group.getConfidence(), group.getAverageSimilarity(), group.getMaxDistance(),
                    new TimeRange(group.getTimeFrom(), group.getTimeTo()),
                    group.getReasons(), group.getRecommendedAssetIds());
        }
    }

    public record TimeRange(Instant from, Instant to) {}
    public record PageResponse<T>(List<T> items, int page, int pageSize, long total) {}
}
