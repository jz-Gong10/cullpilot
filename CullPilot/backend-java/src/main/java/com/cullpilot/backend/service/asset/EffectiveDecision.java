package com.cullpilot.backend.service.asset;

import com.cullpilot.backend.domain.asset.Asset;
import com.cullpilot.backend.domain.asset.AssetDecision;

import java.util.Set;

public final class EffectiveDecision {
    private EffectiveDecision() {}

    public static AssetDecision of(Asset asset, Set<String> userOperatedAssetIds) {
        if (userOperatedAssetIds.contains(asset.getId())) {
            return asset.getDecision();
        }
        return asset.getRecommendation() == null
                ? AssetDecision.REVIEW
                : AssetDecision.valueOf(asset.getRecommendation().name());
    }
}
