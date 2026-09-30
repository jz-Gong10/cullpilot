package com.cullpilot.backend.repository.job;

import com.cullpilot.backend.domain.job.JobError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobErrorRepository extends JpaRepository<JobError, String> {

    List<JobError> findAllByJobIdOrderByCreatedAtAsc(String jobId);

    void deleteAllByJobId(String jobId);
}
