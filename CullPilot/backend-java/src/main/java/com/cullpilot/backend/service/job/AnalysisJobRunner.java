package com.cullpilot.backend.service.job;

import com.cullpilot.backend.analysis.JsonColumns;
import com.cullpilot.backend.domain.asset.AnalysisStatus;
import com.cullpilot.backend.domain.asset.Asset;
import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.domain.group.AssetGroup;
import com.cullpilot.backend.domain.job.Job;
import com.cullpilot.backend.domain.job.JobError;
import com.cullpilot.backend.domain.job.JobStatus;
import com.cullpilot.backend.domain.project.Project;
import com.cullpilot.backend.domain.project.ProjectSettings;
import com.cullpilot.backend.domain.project.ProjectStatus;
import com.cullpilot.backend.integration.AnalysisContract;
import com.cullpilot.backend.integration.PythonApiClient;
import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.group.AssetGroupRepository;
import com.cullpilot.backend.repository.job.JobErrorRepository;
import com.cullpilot.backend.repository.job.JobRepository;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.service.asset.AssetStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AnalysisJobRunner {
    private final JobRepository jobs;
    private final JobErrorRepository errors;
    private final ProjectRepository projects;
    private final AssetRepository assets;
    private final AssetGroupRepository groups;
    private final AssetStorageService storage;
    private final PythonApiClient python;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public AnalysisJobRunner(JobRepository jobs, JobErrorRepository errors, ProjectRepository projects,
                             AssetRepository assets, AssetGroupRepository groups, AssetStorageService storage,
                             PythonApiClient python, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jobs = jobs;
        this.errors = errors;
        this.projects = projects;
        this.assets = assets;
        this.groups = groups;
        this.storage = storage;
        this.python = python;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    public void run(String jobId) {
        if (!claim(jobId)) return;
        try {
            AnalysisContract.Request request = inTransaction(() -> buildRequest(jobId));
            if (request != null && !jobs.findById(jobId).orElseThrow().isForce()
                    && !request.rebuildGroups()
                    && assets.countByProjectIdAndAnalysisStatus(request.projectId(), AnalysisStatus.COMPLETED)
                    == request.assets().size()) {
                inTransaction(() -> completeWithoutChanges(jobId));
                return;
            }
            if (request == null || !markProcessing(jobId)) return;
            for (AnalysisContract.InputAsset asset : request.assets()) {
                storage.load(asset.filePath());
            }
            if (!markStage(jobId, "features", 10)) return;
            AnalysisContract.Result result = python.analyze(request);
            validate(request, result);
            if (!markStage(jobId, "writing", 90)) return;
            inTransaction(() -> persist(jobId, result));
        } catch (Exception exception) {
            fail(jobId, exception);
        }
    }

    private boolean claim(String jobId) {
        return inTransaction(() -> {
            Job job = jobs.findById(jobId).orElse(null);
            if (job == null || job.getStatus() != JobStatus.QUEUED) return false;
            job.setStatus(JobStatus.RUNNING);
            job.setCurrentStage("reading");
            jobs.save(job);
            projects.findById(job.getProjectId()).ifPresent(project -> {
                project.setStatus(ProjectStatus.ANALYZING);
                projects.save(project);
            });
            return true;
        });
    }

    private AnalysisContract.Request buildRequest(String jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.RUNNING) return null;
        Project project = projects.findById(job.getProjectId()).orElseThrow();
        ProjectSettings settings;
        try {
            settings = mapper.readValue(project.getSettingsJson(), ProjectSettings.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Project settings could not be read", exception);
        }
        List<AnalysisContract.InputAsset> input = assets.findAllByProjectIdOrderByCreatedAtAsc(project.getId())
                .stream().map(asset -> new AnalysisContract.InputAsset(asset.getId(), asset.getOriginalPath())).toList();
        List<AnalysisContract.ExistingGroup> existing = job.isRebuildGroups() ? List.of()
                : groups.findAllByProjectIdOrderByGroupNoAsc(project.getId()).stream()
                .map(group -> new AnalysisContract.ExistingGroup(group.getId(),
                        assets.findAllByProjectIdAndGroupId(group.getProjectId(), group.getId(),
                                org.springframework.data.domain.Pageable.unpaged()).getContent()
                                .stream().map(Asset::getId).toList())).toList();
        return new AnalysisContract.Request(project.getId(), input,
                new AnalysisContract.Strategy(settings.getKeepPerGroup(), settings.getStrictness().value(),
                        settings.getContentMode().value(), analysisWeights(settings), settings.getConstraints()),
                job.isRebuildGroups(), existing);
    }

    private Map<String, Double> analysisWeights(ProjectSettings settings) {
        // An empty map tells Python to choose portrait or landscape defaults
        // separately for each generated group. Customized weights remain
        // explicit and are applied consistently across the project.
        Map<String, Double> current = settings.getWeights();
        Map<String, Double> defaults = ProjectSettings.DEFAULT_WEIGHTS;
        if (current.size() != defaults.size()) return current;
        for (Map.Entry<String, Double> entry : defaults.entrySet()) {
            if (Math.abs(current.getOrDefault(entry.getKey(), -1.0) - entry.getValue()) > 1e-6) {
                return current;
            }
        }
        return Map.of();
    }

    private boolean markProcessing(String jobId) {
        return inTransaction(() -> {
            Job job = jobs.findById(jobId).orElseThrow();
            if (job.getStatus() != JobStatus.RUNNING) return false;
            assets.findAllByProjectIdOrderByCreatedAtAsc(job.getProjectId()).forEach(asset -> {
                asset.setAnalysisStatus(AnalysisStatus.PROCESSING);
                assets.save(asset);
            });
            return true;
        });
    }

    private void completeWithoutChanges(String jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.RUNNING) return;
        job.setProcessedCount(job.getTotalCount());
        job.setProgress(100);
        job.setCurrentStage("completed");
        job.setStatus(JobStatus.SUCCEEDED);
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        projects.findById(job.getProjectId()).ifPresent(project -> {
            project.setStatus(ProjectStatus.READY);
            projects.save(project);
        });
    }

    private boolean markStage(String jobId, String stage, int progress) {
        return inTransaction(() -> {
            Job job = jobs.findById(jobId).orElseThrow();
            if (job.getStatus() != JobStatus.RUNNING) return false;
            job.setCurrentStage(stage);
            job.setProgress(progress);
            jobs.save(job);
            return true;
        });
    }

    private void validate(AnalysisContract.Request request, AnalysisContract.Result result) {
        if (!request.projectId().equals(result.projectId()) || result.groups() == null || result.assets() == null) {
            throw new IllegalStateException("Python analysis returned an invalid project or empty result");
        }
        Set<String> expected = new HashSet<>(request.assets().stream().map(AnalysisContract.InputAsset::assetId).toList());
        Map<String, String> membership = new HashMap<>();
        Set<String> groupIds = new HashSet<>();
        for (AnalysisContract.GroupResult group : result.groups()) {
            UUID.fromString(group.id());
            if (!groupIds.add(group.id()) || group.assetIds() == null || group.assetIds().isEmpty()
                    || group.reasons() == null || group.recommendedAssetIds() == null
                    || !validScore(group.confidence())) {
                throw new IllegalStateException("Python analysis returned an invalid group");
            }
            for (String assetId : group.assetIds()) {
                if (!expected.contains(assetId) || membership.put(assetId, group.id()) != null) {
                    throw new IllegalStateException("Python analysis returned invalid group members");
                }
            }
            if (!group.assetIds().containsAll(group.recommendedAssetIds())) {
                throw new IllegalStateException("Python analysis returned invalid recommendations");
            }
        }
        if (!membership.keySet().equals(expected) || result.assets().size() != expected.size()) {
            throw new IllegalStateException("Python analysis did not return every image exactly once");
        }
        Set<String> returned = new HashSet<>();
        Map<String, Set<Integer>> ranks = new HashMap<>();
        for (AnalysisContract.AssetResult asset : result.assets()) {
            if (!returned.add(asset.assetId()) || !asset.groupId().equals(membership.get(asset.assetId()))
                    || !validScore(asset.recommendScore()) || asset.rank() < 1
                    || asset.quality() == null || asset.features() == null || asset.exif() == null
                    || asset.recommendReasons() == null || asset.membershipReason() == null
                    || !ranks.computeIfAbsent(asset.groupId(), ignored -> new HashSet<>()).add(asset.rank())) {
                throw new IllegalStateException("Python analysis returned an invalid image result");
            }
            AssetRecommendation.valueOf(asset.recommendation().toUpperCase(java.util.Locale.ROOT));
        }
        if (!returned.equals(expected)) throw new IllegalStateException("Python analysis omitted images");
    }

    private boolean validScore(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }

    private void persist(String jobId, AnalysisContract.Result result) {
        Job job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.RUNNING) return;
        String projectId = job.getProjectId();
        groups.deleteAllByProjectId(projectId);
        groups.flush();
        int number = 1;
        for (AnalysisContract.GroupResult group : result.groups()) {
            groups.save(new AssetGroup(projectId, number++, group));
        }
        for (AnalysisContract.AssetResult item : result.assets()) {
            Asset asset = assets.findByIdAndProjectId(item.assetId(), projectId).orElseThrow();
            asset.setAnalysisResult(item.groupId(), item.rank(),
                    AssetRecommendation.valueOf(item.recommendation().toUpperCase(java.util.Locale.ROOT)),
                    item.recommendScore(), JsonColumns.write(item.recommendReasons()), item.membershipReason(),
                    JsonColumns.write(item.quality()), JsonColumns.write(item.features()), JsonColumns.write(item.exif()));
            assets.save(asset);
        }
        job.setProcessedCount(result.assets().size());
        job.setProgress(100);
        job.setCurrentStage("completed");
        job.setStatus(JobStatus.SUCCEEDED);
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        projects.findById(projectId).ifPresent(project -> {
            project.setStatus(ProjectStatus.READY);
            projects.save(project);
        });
    }

    private void fail(String jobId, Exception exception) {
        inTransaction(() -> {
            Job job = jobs.findById(jobId).orElse(null);
            if (job == null || job.getStatus() != JobStatus.RUNNING) return;
            String message = exception.getMessage() == null ? "Analysis failed" : exception.getMessage();
            if (message.length() > 500) message = message.substring(0, 500);
            errors.save(new JobError(UUID.randomUUID().toString(), jobId, null,
                    job.getCurrentStage(), "ANALYSIS_JOB_FAILED", message, Instant.now()));
            assets.findAllByProjectIdAndAnalysisStatus(job.getProjectId(), AnalysisStatus.PROCESSING)
                    .forEach(asset -> asset.setAnalysisStatus(AnalysisStatus.FAILED));
            job.setErrorCount(job.getTotalCount());
            job.setProcessedCount(job.getTotalCount());
            job.setProgress(100);
            job.setErrorMessage(message);
            job.setCurrentStage("failed");
            job.setStatus(JobStatus.FAILED);
            job.setFinishedAt(Instant.now());
            jobs.save(job);
            projects.findById(job.getProjectId()).ifPresent(project -> {
                project.setStatus(ProjectStatus.FAILED);
                projects.save(project);
            });
        });
    }

    private <T> T inTransaction(Supplier<T> action) { return transaction.execute(status -> action.get()); }
    private void inTransaction(Runnable action) { transaction.executeWithoutResult(status -> action.run()); }
}
