package com.cullpilot.backend.repository.asset;

import com.cullpilot.backend.domain.asset.Asset;
import com.cullpilot.backend.domain.asset.AssetDecision;
import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.domain.asset.AnalysisStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface AssetRepository extends JpaRepository<Asset, String> {

    Optional<Asset> findByProjectIdAndSha256(String projectId, String sha256);

    Optional<Asset> findByIdAndProjectId(String id, String projectId);

    @Query("""
            select a from Asset a
            where a.projectId = :projectId
              and (:decision is null or a.decision = :decision)
              and (:recommendation is null or a.recommendation = :recommendation)
              and (:groupId is null or a.groupId = :groupId)
              and (:analysisStatus is null or a.analysisStatus = :analysisStatus)
            """)
    Page<Asset> search(
            @Param("projectId") String projectId,
            @Param("decision") AssetDecision decision,
            @Param("recommendation") AssetRecommendation recommendation,
            @Param("groupId") String groupId,
            @Param("analysisStatus") AnalysisStatus analysisStatus,
            Pageable pageable);

    long countByProjectId(String projectId);

    long countByProjectIdAndAnalysisStatus(String projectId, AnalysisStatus analysisStatus);

    long countByProjectIdAndDecision(String projectId, AssetDecision decision);

    long countByProjectIdAndRecommendation(String projectId, AssetRecommendation recommendation);

    @Query("select count(distinct a.groupId) from Asset a where a.projectId = :projectId and a.groupId is not null")
    long countGroups(@Param("projectId") String projectId);

    List<Asset> findAllByProjectIdOrderByCreatedAtAsc(String projectId);

    List<Asset> findAllByProjectIdAndAnalysisStatus(String projectId, AnalysisStatus analysisStatus);

    Page<Asset> findAllByProjectIdAndGroupId(String projectId, String groupId, Pageable pageable);

    long countByProjectIdAndGroupId(String projectId, String groupId);

    void deleteAllByProjectId(String projectId);
}
