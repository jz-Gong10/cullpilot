package com.cullpilot.backend.repository.group;

import com.cullpilot.backend.domain.group.AssetGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetGroupRepository extends JpaRepository<AssetGroup, String> {
    Page<AssetGroup> findAllByProjectId(String projectId, Pageable pageable);
    List<AssetGroup> findAllByProjectIdOrderByGroupNoAsc(String projectId);
    void deleteAllByProjectId(String projectId);
    long countByProjectId(String projectId);
}
