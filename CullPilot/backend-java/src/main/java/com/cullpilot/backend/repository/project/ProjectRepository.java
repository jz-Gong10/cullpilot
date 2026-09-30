package com.cullpilot.backend.repository.project;

import com.cullpilot.backend.domain.project.Project;
import com.cullpilot.backend.domain.project.ProjectStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, String> {

    Optional<Project> findByIdAndOwnerIdAndDeletedAtIsNull(String id, String ownerId);

    Page<Project> findAllByOwnerIdAndDeletedAtIsNull(String ownerId, Pageable pageable);

    Page<Project> findAllByOwnerIdAndDeletedAtIsNullAndStatus(
            String ownerId, ProjectStatus status, Pageable pageable);
}
