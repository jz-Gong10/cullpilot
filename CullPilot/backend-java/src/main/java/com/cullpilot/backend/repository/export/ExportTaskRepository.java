package com.cullpilot.backend.repository.export;

import com.cullpilot.backend.domain.export.ExportTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.Collection;

public interface ExportTaskRepository extends JpaRepository<ExportTask, String> {
    Optional<ExportTask> findByIdAndProjectId(String id, String projectId);
    boolean existsByProjectIdAndStatusIn(String projectId, Collection<ExportTask.Status> statuses);
    void deleteAllByProjectId(String projectId);
}
