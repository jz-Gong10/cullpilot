package com.cullpilot.backend.service.job;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.job.JobRequests.AnalyzeRequest;
import com.cullpilot.backend.api.job.JobResponses.DecisionCounts;
import com.cullpilot.backend.api.job.JobResponses.JobResponse;
import com.cullpilot.backend.api.job.JobResponses.ProjectSummaryResponse;
import com.cullpilot.backend.domain.asset.AssetDecision;
import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.domain.asset.AnalysisStatus;
import com.cullpilot.backend.domain.job.Job;
import com.cullpilot.backend.domain.job.JobStatus;
import com.cullpilot.backend.domain.job.JobType;
import com.cullpilot.backend.domain.project.Project;
import com.cullpilot.backend.domain.project.ProjectStatus;
import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.job.JobErrorRepository;
import com.cullpilot.backend.repository.job.JobRepository;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.security.CurrentUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class JobService {

    private static final Set<JobStatus> ACTIVE_STATUSES =
            EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING);
    private static final Set<JobStatus> RETRYABLE_STATUSES =
            EnumSet.of(JobStatus.FAILED, JobStatus.PARTIAL_FAILED, JobStatus.CANCELLED);

    private final JobRepository jobRepository;
    private final JobErrorRepository jobErrorRepository;
    private final ProjectRepository projectRepository;
    private final AssetRepository assetRepository;
    private final AnalysisJobRunner analysisJobRunner;
    private final Executor analysisTaskExecutor;
    private final CurrentUser currentUser;

    public JobService(
            JobRepository jobRepository,
            JobErrorRepository jobErrorRepository,
            ProjectRepository projectRepository,
            AssetRepository assetRepository,
            AnalysisJobRunner analysisJobRunner,
            @Qualifier("analysisTaskExecutor") Executor analysisTaskExecutor,
            CurrentUser currentUser) {
        this.jobRepository = jobRepository;
        this.jobErrorRepository = jobErrorRepository;
        this.projectRepository = projectRepository;
        this.assetRepository = assetRepository;
        this.analysisJobRunner = analysisJobRunner;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.currentUser = currentUser;
    }

    @Transactional
    public JobResponse startAnalysis(String projectId, AnalyzeRequest request, String idempotencyKey) {
        Project project = findProject(projectId);
        ensureProjectCanAnalyze(project);

        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedKey != null) {
            var existing = jobRepository.findByIdempotencyKey(projectId, JobType.ANALYSIS, normalizedKey);
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        ensureNoActiveJob(projectId);
        int totalCount = Math.toIntExact(assetRepository.countByProjectId(projectId));
        if (totalCount == 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PROJECT_HAS_NO_ASSETS",
                    "The project has no images to analyze");
        }

        AnalyzeRequest safeRequest = request == null ? new AnalyzeRequest(false, true) : request;
        Job job = createJob(
                project,
                totalCount,
                normalizedKey,
                safeRequest.forceValue(),
                safeRequest.rebuildGroupsValue());
        scheduleAfterCommit(job.getId());
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public JobResponse get(String jobId) {
        return toResponse(findJob(jobId));
    }

    @Transactional
    public JobResponse cancel(String jobId) {
        Job job = findJob(jobId);
        if (!ACTIVE_STATUSES.contains(job.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "JOB_NOT_CANCELLABLE",
                    "Only queued or running jobs can be cancelled");
        }

        job.setStatus(JobStatus.CANCELLED);
        job.setCurrentStage("cancelled");
        job.setFinishedAt(Instant.now());
        jobRepository.save(job);

        Project project = findProject(job.getProjectId());
        if (project.getStatus() == ProjectStatus.ANALYZING) {
            project.setStatus(ProjectStatus.CREATED);
            projectRepository.save(project);
        }
        assetRepository.findAllByProjectIdAndAnalysisStatus(
                        job.getProjectId(), AnalysisStatus.PROCESSING)
                .forEach(asset -> asset.setAnalysisStatus(AnalysisStatus.PENDING));
        return toResponse(job);
    }

    @Transactional
    public JobResponse retry(String jobId) {
        Job previous = findJob(jobId);
        if (!RETRYABLE_STATUSES.contains(previous.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "JOB_NOT_RETRYABLE",
                    "Only failed or cancelled jobs can be retried");
        }

        Project project = findProject(previous.getProjectId());
        ensureProjectCanAnalyze(project);
        ensureNoActiveJob(project.getId());
        int totalCount = Math.toIntExact(assetRepository.countByProjectId(project.getId()));
        if (totalCount == 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PROJECT_HAS_NO_ASSETS",
                    "The project has no images to analyze");
        }

        Job retry = createJob(
                project,
                totalCount,
                null,
                false,
                previous.isRebuildGroups());
        scheduleAfterCommit(retry.getId());
        return toResponse(retry);
    }

    @Transactional(readOnly = true)
    public ProjectSummaryResponse summary(String projectId) {
        Project project = findProject(projectId);
        long assetCount = assetRepository.countByProjectId(projectId);
        long analyzedCount = assetRepository.countByProjectIdAndAnalysisStatus(
                projectId, AnalysisStatus.COMPLETED);
        long groupCount = assetRepository.countGroups(projectId);
        DecisionCounts decisions = new DecisionCounts(
                assetRepository.countByProjectIdAndDecision(projectId, AssetDecision.KEEP),
                assetRepository.countByProjectIdAndDecision(projectId, AssetDecision.REVIEW),
                assetRepository.countByProjectIdAndDecision(projectId, AssetDecision.REJECT));
        DecisionCounts recommendations = new DecisionCounts(
                assetRepository.countByProjectIdAndRecommendation(projectId, AssetRecommendation.KEEP),
                assetRepository.countByProjectIdAndRecommendation(projectId, AssetRecommendation.REVIEW),
                assetRepository.countByProjectIdAndRecommendation(projectId, AssetRecommendation.REJECT));

        String activeJobId = jobRepository
                .findFirstByProjectIdAndTypeAndStatusInOrderByCreatedAtDesc(
                        projectId, JobType.ANALYSIS, ACTIVE_STATUSES)
                .map(Job::getId)
                .orElse(null);

        List<String> warnings = jobRepository
                .findFirstByProjectIdAndTypeOrderByCreatedAtDesc(projectId, JobType.ANALYSIS)
                .map(job -> jobErrorRepository.findAllByJobIdOrderByCreatedAtAsc(job.getId())
                        .stream()
                        .map(error -> error.getMessage())
                        .distinct()
                        .toList())
                .orElse(List.of());

        return new ProjectSummaryResponse(
                projectId,
                project.getStatus(),
                assetCount,
                analyzedCount,
                groupCount,
                decisions,
                recommendations,
                activeJobId,
                warnings);
    }

    private Job createJob(
            Project project,
            int totalCount,
            String idempotencyKey,
            boolean force,
            boolean rebuildGroups) {
        Instant now = Instant.now();
        Job job = new Job(
                UUID.randomUUID().toString(),
                project.getId(),
                JobType.ANALYSIS,
                totalCount,
                idempotencyKey,
                force,
                rebuildGroups,
                now);
        project.setStatus(ProjectStatus.ANALYZING);
        projectRepository.save(project);
        return jobRepository.saveAndFlush(job);
    }

    private void scheduleAfterCommit(String jobId) {
        Runnable task = () -> analysisTaskExecutor.execute(() -> analysisJobRunner.run(jobId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void ensureNoActiveJob(String projectId) {
        jobRepository.findFirstByProjectIdAndTypeAndStatusInOrderByCreatedAtDesc(
                        projectId, JobType.ANALYSIS, ACTIVE_STATUSES)
                .ifPresent(job -> {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "JOB_ALREADY_RUNNING",
                            "An analysis job is already running",
                            java.util.Map.of("jobId", job.getId()));
                });
    }

    private void ensureProjectCanAnalyze(Project project) {
        if (project.getStatus() == ProjectStatus.DELETING) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PROJECT_OPERATION_NOT_ALLOWED",
                    "The project is being deleted");
        }
    }

    private Project findProject(String projectId) {
        try {
            UUID.fromString(projectId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found");
        }
        return projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, currentUser.id())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
    }

    private Job findJob(String jobId) {
        try {
            UUID.fromString(jobId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found");
        }
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found"));
        findProject(job.getProjectId());
        return job;
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim();
        if (normalized.length() > 200) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be at most 200 characters");
        }
        return normalized;
    }

    private JobResponse toResponse(Job job) {
        return JobResponse.from(job, jobErrorRepository.findAllByJobIdOrderByCreatedAtAsc(job.getId()));
    }
}
