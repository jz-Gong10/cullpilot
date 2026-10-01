package com.cullpilot.backend.repository.aigc;

import com.cullpilot.backend.domain.aigc.AigcEdit;
import com.cullpilot.backend.domain.aigc.AigcEditStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface AigcEditRepository extends JpaRepository<AigcEdit, String> {
    Optional<AigcEdit> findByIdAndProjectId(String id, String projectId);
    List<AigcEdit> findAllByProjectIdOrderByCreatedAtDesc(String projectId);
    List<AigcEdit> findAllByProjectIdAndStatusOrderByCreatedAtAsc(String projectId, AigcEditStatus status);
    List<AigcEdit> findAllByProjectIdAndStatusInOrderByCreatedAtAsc(
            String projectId, Collection<AigcEditStatus> statuses);
    List<AigcEdit> findAllByAssetId(String assetId);
    boolean existsByAssetIdAndStatusIn(String assetId, Collection<AigcEditStatus> statuses);
    void deleteAllByAssetId(String assetId);
    boolean existsByProjectIdAndStatusIn(String projectId, Collection<AigcEditStatus> statuses);
    void deleteAllByProjectId(String projectId);
}
