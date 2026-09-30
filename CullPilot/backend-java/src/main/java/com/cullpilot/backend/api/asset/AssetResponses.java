package com.cullpilot.backend.api.asset;

import com.cullpilot.backend.analysis.JsonColumns;
import com.cullpilot.backend.domain.asset.Asset;
import com.cullpilot.backend.domain.asset.AssetDecision;
import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.domain.asset.AnalysisStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AssetResponses {

    private AssetResponses() {
    }

    public record AssetResponse(
            String id,
            String projectId,
            String originalName,
            String mimeType,
            long sizeBytes,
            int width,
            int height,
            String sha256,
            AnalysisStatus analysisStatus,
            AssetDecision decision,
            AssetRecommendation recommendation,
            String groupId,
            Integer rank,
            Double recommendScore,
            List<String> recommendReasons,
            String membershipReason,
            String thumbnailUrl,
            String originalUrl,
            Map<String, Object> exif,
            Map<String, Object> quality,
            Map<String, Object> features,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        public static AssetResponse from(Asset asset) {
            return new AssetResponse(
                    asset.getId(),
                    asset.getProjectId(),
                    asset.getOriginalName(),
                    asset.getMimeType(),
                    asset.getSizeBytes(),
                    asset.getWidth(),
                    asset.getHeight(),
                    asset.getSha256(),
                    asset.getAnalysisStatus(),
                    asset.getDecision(),
                    asset.getRecommendation(),
                    asset.getGroupId(),
                    asset.getRank(),
                    asset.getRecommendScore(),
                    JsonColumns.strings(asset.getRecommendReasonsJson()),
                    asset.getMembershipReason(),
                    "/api/v1/assets/" + asset.getId() + "/thumbnail",
                    "/api/v1/assets/" + asset.getId() + "/original",
                    JsonColumns.object(asset.getExifJson()),
                    JsonColumns.object(asset.getQualityJson()),
                    JsonColumns.object(asset.getFeaturesJson()),
                    asset.getVersion(),
                    asset.getCreatedAt(),
                    asset.getUpdatedAt());
        }
    }

    public record PageResponse<T>(
            List<T> items,
            int page,
            int pageSize,
            long total) {
    }

    public record AcceptedAsset(
            String id,
            String originalName,
            String mimeType,
            long sizeBytes,
            int width,
            int height,
            AnalysisStatus analysisStatus,
            AssetDecision decision,
            AssetRecommendation recommendation,
            String thumbnailUrl,
            String originalUrl) {

        public static AcceptedAsset from(Asset asset) {
            return new AcceptedAsset(
                    asset.getId(),
                    asset.getOriginalName(),
                    asset.getMimeType(),
                    asset.getSizeBytes(),
                    asset.getWidth(),
                    asset.getHeight(),
                    asset.getAnalysisStatus(),
                    asset.getDecision(),
                    asset.getRecommendation(),
                    "/api/v1/assets/" + asset.getId() + "/thumbnail",
                    "/api/v1/assets/" + asset.getId() + "/original");
        }
    }

    public record DuplicateAsset(
            String originalName,
            String sha256,
            String existingAssetId) {
    }

    public record FailedAsset(
            String originalName,
            String code,
            String message) {
    }

    public record UploadBatchResponse(
            String batchId,
            List<AcceptedAsset> accepted,
            List<DuplicateAsset> duplicates,
            List<FailedAsset> failed) {
    }
}
