package com.cullpilot.backend.repository.asset;

import com.cullpilot.backend.domain.asset.DecisionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DecisionHistoryRepository extends JpaRepository<DecisionHistory, String> {
    List<DecisionHistory> findAllByAssetIdIn(Collection<String> assetIds);
    void deleteAllByAssetIdIn(Collection<String> assetIds);
}
