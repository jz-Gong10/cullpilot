package com.cullpilot.backend.repository.job;

import com.cullpilot.backend.domain.job.Job;
import com.cullpilot.backend.domain.job.JobStatus;
import com.cullpilot.backend.domain.job.JobType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, String> {

    @Query("select j from Job j where j.projectId = :projectId and j.type = :type "
            + "and j.idempotencyKey = :idempotencyKey")
    Optional<Job> findByIdempotencyKey(
            @Param("projectId") String projectId,
            @Param("type") JobType type,
            @Param("idempotencyKey") String idempotencyKey);

    Optional<Job> findFirstByProjectIdAndTypeAndStatusInOrderByCreatedAtDesc(
            String projectId, JobType type, Collection<JobStatus> statuses);

    Optional<Job> findFirstByProjectIdAndTypeOrderByCreatedAtDesc(String projectId, JobType type);

    List<Job> findAllByProjectId(String projectId);

    void deleteAllByProjectId(String projectId);
}
