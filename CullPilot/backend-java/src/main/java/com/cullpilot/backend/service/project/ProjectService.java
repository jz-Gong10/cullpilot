package com.cullpilot.backend.service.project;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.project.ProjectRequests.CreateProjectRequest;
import com.cullpilot.backend.api.project.ProjectRequests.PrivacySettingsPatch;
import com.cullpilot.backend.api.project.ProjectRequests.ProjectSettingsPatch;
import com.cullpilot.backend.api.project.ProjectResponses.CleanupJobResponse;
import com.cullpilot.backend.api.project.ProjectResponses.DeleteProjectResponse;
import com.cullpilot.backend.api.project.ProjectResponses.PageResponse;
import com.cullpilot.backend.api.project.ProjectResponses.ProjectResponse;
import com.cullpilot.backend.domain.project.Project;
import com.cullpilot.backend.domain.asset.AnalysisStatus;
import com.cullpilot.backend.domain.project.ProjectPrivacySettings;
import com.cullpilot.backend.domain.project.ProjectSettings;
import com.cullpilot.backend.domain.project.ProjectStatus;
import com.cullpilot.backend.domain.project.ContentMode;
import com.cullpilot.backend.domain.project.Strictness;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.job.JobErrorRepository;
import com.cullpilot.backend.repository.job.JobRepository;
import com.cullpilot.backend.repository.export.ExportTaskRepository;
import com.cullpilot.backend.repository.group.AssetGroupRepository;
import com.cullpilot.backend.domain.job.JobStatus;
import com.cullpilot.backend.domain.job.JobType;
import com.cullpilot.backend.repository.asset.DecisionHistoryRepository;
import com.cullpilot.backend.domain.export.ExportTask;
import com.cullpilot.backend.domain.aigc.AigcEditStatus;
import com.cullpilot.backend.repository.aigc.AigcEditRepository;
import com.cullpilot.backend.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectService {

    private static final Set<String> WEIGHT_KEYS = Set.of(
            "sharpness", "eyesOpen", "expression", "exposure", "composition", "motion");
    private static final Set<String> CONSTRAINT_KEYS = Set.of(
            "avoidSevereBlur", "avoidSevereOverexposure", "allowMildMotionBlur", "preferFrontFacing");

    private final ProjectRepository projectRepository;
    private final AssetRepository assetRepository;
    private final ProjectStorageService storageService;
    private final JobRepository jobRepository;
    private final JobErrorRepository jobErrorRepository;
    private final ObjectMapper objectMapper;
    private final CurrentUser currentUser;
    private final ExportTaskRepository exportTaskRepository;
    private final DecisionHistoryRepository decisionHistoryRepository;
    private final AssetGroupRepository groupRepository;
    private final AigcEditRepository aigcEditRepository;

    public ProjectService(
            ProjectRepository projectRepository,
            AssetRepository assetRepository,
            ProjectStorageService storageService,
            JobRepository jobRepository,
            JobErrorRepository jobErrorRepository,
            ObjectMapper objectMapper,
            CurrentUser currentUser,
            ExportTaskRepository exportTaskRepository,
            DecisionHistoryRepository decisionHistoryRepository,
            AssetGroupRepository groupRepository,
            AigcEditRepository aigcEditRepository) {
        this.projectRepository = projectRepository;
        this.assetRepository = assetRepository;
        this.storageService = storageService;
        this.jobRepository = jobRepository;
        this.jobErrorRepository = jobErrorRepository;
        this.objectMapper = objectMapper;
        this.currentUser = currentUser;
        this.exportTaskRepository = exportTaskRepository;
        this.decisionHistoryRepository = decisionHistoryRepository;
        this.groupRepository = groupRepository;
        this.aigcEditRepository = aigcEditRepository;
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        ProjectSettings settings = applyPatch(ProjectSettings.defaults(), request.settings());
        Instant now = Instant.now();
        Project project = new Project(
                UUID.randomUUID().toString(),
                currentUser.id(),
                request.name().trim(),
                ProjectStatus.CREATED,
                serializeSettings(settings),
                now);

        return toResponse(projectRepository.save(project), settings);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> list(String statusValue, int page, int pageSize, String sortValue) {
        validatePage(page, pageSize);
        Pageable pageable = createPageable(page, pageSize, sortValue);
        ProjectStatus status = parseStatus(statusValue);
        String ownerId = currentUser.id();
        Page<Project> result = status == null
                ? projectRepository.findAllByOwnerIdAndDeletedAtIsNull(ownerId, pageable)
                : projectRepository.findAllByOwnerIdAndDeletedAtIsNullAndStatus(ownerId, status, pageable);

        return new PageResponse<>(
                result.getContent().stream().map(this::toResponse).toList(),
                page,
                pageSize,
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(String projectId) {
        return toResponse(findProject(projectId));
    }

    @Transactional
    public ProjectResponse updateSettings(String projectId, ProjectSettingsPatch patch) {
        Project project = findProject(projectId);
        ensureProjectCanChange(project);

        ProjectSettings settings = applyPatch(readSettings(project), patch);
        project.setSettingsJson(serializeSettings(settings));
        return toResponse(projectRepository.save(project), settings);
    }

    @Transactional
    public DeleteProjectResponse delete(String projectId) {
        Project project = findProject(projectId);
        if (project.getStatus() == ProjectStatus.DELETING) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PROJECT_DELETE_ALREADY_RUNNING",
                    "项目正在删除");
        }
        if (exportTaskRepository.existsByProjectIdAndStatusIn(projectId,
                Set.of(ExportTask.Status.QUEUED, ExportTask.Status.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPORT_IN_PROGRESS", "项目正在导出，暂时不能删除");
        }

        if (aigcEditRepository.existsByProjectIdAndStatusIn(projectId,
                Set.of(AigcEditStatus.QUEUED, AigcEditStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "AIGC_IN_PROGRESS", "Project has image-edit tasks in progress");
        }

        if (jobRepository.findFirstByProjectIdAndTypeAndStatusInOrderByCreatedAtDesc(
                projectId, JobType.ANALYSIS,
                Set.of(JobStatus.QUEUED, JobStatus.RUNNING)).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_IN_PROGRESS",
                    "The project is being analyzed");
        }

        // Complete cleanup in this transaction; the returned job keeps the public contract stable.
        Instant createdAt = Instant.now();
        String cleanupJobId = UUID.randomUUID().toString();
        project.setStatus(ProjectStatus.DELETING);
        projectRepository.saveAndFlush(project);

        jobRepository.findAllByProjectId(project.getId())
                .forEach(job -> jobErrorRepository.deleteAllByJobId(job.getId()));
        jobRepository.deleteAllByProjectId(project.getId());
        exportTaskRepository.deleteAllByProjectId(project.getId());
        aigcEditRepository.deleteAllByProjectId(project.getId());
        var assetIds = assetRepository
                .findAllByProjectIdOrderByCreatedAtAsc(project.getId()).stream()
                .map(com.cullpilot.backend.domain.asset.Asset::getId).toList();
        if (!assetIds.isEmpty()) {
            decisionHistoryRepository.deleteAllByAssetIdIn(assetIds);
        }
        assetRepository.deleteAllByProjectId(project.getId());
        groupRepository.deleteAllByProjectId(project.getId());
        storageService.deleteProjectStorage(project.getId());
        projectRepository.delete(project);
        projectRepository.flush();

        CleanupJobResponse job = new CleanupJobResponse(
                cleanupJobId,
                "projectCleanup",
                "succeeded",
                createdAt,
                Instant.now());
        return new DeleteProjectResponse(project.getId(), job);
    }

    private Project findProject(String projectId) {
        validateProjectId(projectId);
        return projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, currentUser.id())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PROJECT_NOT_FOUND",
                        "项目不存在"));
    }

    private void ensureProjectCanChange(Project project) {
        if (project.getStatus() == ProjectStatus.DELETING) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PROJECT_OPERATION_NOT_ALLOWED",
                    "项目正在删除，暂时不能修改");
        }
    }

    private void validateProjectId(String projectId) {
        try {
            UUID.fromString(projectId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        }
    }

    private ProjectResponse toResponse(Project project) {
        return toResponse(project, readSettings(project));
    }

    private ProjectResponse toResponse(Project project, ProjectSettings settings) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getStatus(),
                settings,
                assetRepository.countByProjectId(project.getId()),
                groupRepository.countByProjectId(project.getId()),
                project.getCreatedAt(),
                project.getUpdatedAt());
    }

    private ProjectSettings readSettings(Project project) {
        try {
            return normalizeSettings(objectMapper.readValue(project.getSettingsJson(), ProjectSettings.class));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "PROJECT_SETTINGS_CORRUPTED",
                    "项目设置数据损坏");
        }
    }

    private String serializeSettings(ProjectSettings settings) {
        try {
            return objectMapper.writeValueAsString(normalizeSettings(settings));
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "PROJECT_SETTINGS_SERIALIZATION_FAILED",
                    "项目设置保存失败");
        }
    }

    private ProjectSettings applyPatch(ProjectSettings base, ProjectSettingsPatch patch) {
        ProjectSettings result = base.copy();
        if (patch == null) {
            return normalizeSettings(result);
        }

        if (patch.keepPerGroup() != null) {
            result.setKeepPerGroup(patch.keepPerGroup());
        }
        if (patch.strictness() != null) {
            result.setStrictness(parseStrictness(patch.strictness()));
        }
        if (patch.contentMode() != null) {
            result.setContentMode(parseContentMode(patch.contentMode()));
        }
        if (patch.weights() != null) {
            result.setWeights(patch.weights().isEmpty()
                    ? ProjectSettings.DEFAULT_WEIGHTS
                    : mergeWeights(result.getWeights(), patch.weights()));
        }
        if (patch.constraints() != null) {
            result.setConstraints(mergeConstraints(result.getConstraints(), patch.constraints()));
        }
        if (patch.privacy() != null) {
            result.setPrivacy(mergePrivacy(result.getPrivacy(), patch.privacy()));
        }
        return normalizeSettings(result);
    }

    private ProjectSettings normalizeSettings(ProjectSettings settings) {
        if (settings == null) {
            return ProjectSettings.defaults();
        }
        if (settings.getKeepPerGroup() < 1 || settings.getKeepPerGroup() > 10) {
            throw invalidSettings("keepPerGroup 必须在 1 到 10 之间");
        }
        if (settings.getStrictness() == null) {
            settings.setStrictness(Strictness.STANDARD);
        }
        if (settings.getContentMode() == null) {
            settings.setContentMode(ContentMode.AUTO);
        }
        settings.setWeights(normalizeWeights(ProjectSettings.DEFAULT_WEIGHTS, settings.getWeights()));
        settings.setConstraints(mergeConstraints(ProjectSettings.DEFAULT_CONSTRAINTS, settings.getConstraints()));
        if (settings.getPrivacy() == null) {
            settings.setPrivacy(new ProjectPrivacySettings());
        }
        return settings;
    }

    private Map<String, Double> mergeWeights(Map<String, Double> base, Map<String, Double> patch) {
        Map<String, Double> merged = new LinkedHashMap<>(base);
        for (Map.Entry<String, Double> entry : patch.entrySet()) {
            validateWeightKey(entry.getKey());
            Double value = entry.getValue();
            if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
                throw invalidSettings("权重必须是 0 到 1 之间的数字");
            }
            merged.put(entry.getKey(), value);
        }
        return normalizeWeights(ProjectSettings.DEFAULT_WEIGHTS, merged);
    }

    private Map<String, Double> normalizeWeights(Map<String, Double> defaults, Map<String, Double> values) {
        Map<String, Double> merged = new LinkedHashMap<>(defaults);
        if (values != null) {
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                validateWeightKey(entry.getKey());
                Double value = entry.getValue();
                if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
                    throw invalidSettings("权重必须是 0 到 1 之间的数字");
                }
                merged.put(entry.getKey(), value);
            }
        }

        double sum = merged.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            throw invalidSettings("权重总和必须大于 0");
        }
        merged.replaceAll((key, value) -> value / sum);
        return merged;
    }

    private Map<String, Boolean> mergeConstraints(
            Map<String, Boolean> base,
            Map<String, Boolean> patch) {
        Map<String, Boolean> merged = new LinkedHashMap<>(base);
        if (patch == null) {
            return merged;
        }
        for (Map.Entry<String, Boolean> entry : patch.entrySet()) {
            if (!CONSTRAINT_KEYS.contains(entry.getKey()) || entry.getValue() == null) {
                throw invalidSettings("存在不支持的筛选约束");
            }
            merged.put(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    private ProjectPrivacySettings mergePrivacy(ProjectPrivacySettings base, PrivacySettingsPatch patch) {
        ProjectPrivacySettings merged = base == null ? new ProjectPrivacySettings() : base.copy();
        if (patch.sendThumbnailsToProvider() != null) {
            merged.setSendThumbnailsToProvider(patch.sendThumbnailsToProvider());
        }
        if (patch.stripGpsOnExport() != null) {
            merged.setStripGpsOnExport(patch.stripGpsOnExport());
        }
        return merged;
    }

    private void validateWeightKey(String key) {
        if (key == null || !WEIGHT_KEYS.contains(key)) {
            throw invalidSettings("存在不支持的权重字段");
        }
    }

    private ProjectStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ProjectStatus.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROJECT_STATUS", "项目状态不合法");
        }
    }

    private Strictness parseStrictness(String value) {
        try {
            return Strictness.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw invalidSettings("strictness 必须是 loose、standard 或 strict");
        }
    }

    private ContentMode parseContentMode(String value) {
        try {
            return ContentMode.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw invalidSettings(
                    "contentMode 必须是 auto、portrait、landscape 或 mixed");
        }
    }

    private Pageable createPageable(int page, int pageSize, String sortValue) {
        String value = sortValue == null || sortValue.isBlank() ? "createdAt:desc" : sortValue;
        String[] parts = value.split(":", 2);
        String property = switch (parts[0]) {
            case "name", "createdAt", "updatedAt" -> parts[0];
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "排序字段不支持");
        };
        Sort.Direction direction;
        try {
            direction = parts.length == 1
                    ? Sort.Direction.ASC
                    : Sort.Direction.fromString(parts[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "排序方向必须是 asc 或 desc");
        }
        return PageRequest.of(page - 1, pageSize, Sort.by(direction, property));
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "分页参数不合法");
        }
    }

    private ApiException invalidSettings(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PROJECT_SETTINGS", message);
    }
}
