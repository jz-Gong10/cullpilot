package com.cullpilot.backend.api.asset;

import com.cullpilot.backend.domain.asset.AssetDecision;
import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.domain.asset.AnalysisStatus;

public final class AssetRequests {

    private AssetRequests() {
    }

    public record AssetFilters(
            AssetDecision decision,
            AssetRecommendation recommendation,
            String groupId,
            AnalysisStatus analysisStatus) {
    }

    public record UpdateDecisionRequest(
            AssetDecision decision,
            Long expectedVersion) {
    }
}
