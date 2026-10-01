package com.cullpilot.backend.service.asset;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.asset.AssetResponses.AcceptedAsset;
import com.cullpilot.backend.api.asset.AssetResponses.AssetResponse;
import com.cullpilot.backend.api.asset.AssetResponses.DuplicateAsset;
import com.cullpilot.backend.api.asset.AssetResponses.FailedAsset;
import com.cullpilot.backend.api.asset.AssetResponses.PageResponse;
import com.cullpilot.backend.api.asset.AssetResponses.UploadBatchResponse;
import com.cullpilot.backend.api.asset.AssetRequests.UpdateDecisionRequest;
import com.cullpilot.backend.domain.asset.DecisionHistory;
import com.cullpilot.backend.repository.asset.DecisionHistoryRepository;
import com.cullpilot.backend.domain.asset.AnalysisStatus;
import com.cullpilot.backend.domain.asset.Asset;
import com.cullpilot.backend.domain.asset.AssetDecision;
import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.domain.project.Project;
import com.cullpilot.backend.domain.project.ProjectStatus;
import com.cullpilot.backend.domain.aigc.AigcEdit;
import com.cullpilot.backend.domain.aigc.AigcEditStatus;
import com.cullpilot.backend.domain.export.ExportTask;
import com.cullpilot.backend.domain.job.JobStatus;
import com.cullpilot.backend.domain.job.JobType;
import com.cullpilot.backend.security.CurrentUser;
import com.cullpilot.backend.repository.aigc.AigcEditRepository;
import com.cullpilot.backend.repository.export.ExportTaskRepository;
import com.cullpilot.backend.repository.group.AssetGroupRepository;
import com.cullpilot.backend.repository.job.JobRepository;
import com.cullpilot.backend.service.aigc.AigcStorageService;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AssetService {

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final long MAX_PROJECT_ASSETS = 1000;

    private final AssetRepository assetRepository;
    private final ProjectRepository projectRepository;
    private final AssetStorageService storageService;
    private final CurrentUser currentUser;
    private final DecisionHistoryRepository decisionHistoryRepository;
    private final JobRepository jobRepository;
    private final ExportTaskRepository exportTaskRepository;
    private final AssetGroupRepository groupRepository;
    private final AigcEditRepository aigcEditRepository;
    private final AigcStorageService aigcStorageService;

    public AssetService(
            AssetRepository assetRepository,
            ProjectRepository projectRepository,
            AssetStorageService storageService,
            CurrentUser currentUser,
            DecisionHistoryRepository decisionHistoryRepository,
            JobRepository jobRepository,
            ExportTaskRepository exportTaskRepository,
            AssetGroupRepository groupRepository,
            AigcEditRepository aigcEditRepository,
            AigcStorageService aigcStorageService) {
        this.assetRepository = assetRepository;
        this.projectRepository = projectRepository;
        this.storageService = storageService;
        this.currentUser = currentUser;
        this.decisionHistoryRepository = decisionHistoryRepository;
        this.jobRepository = jobRepository;
        this.exportTaskRepository = exportTaskRepository;
        this.groupRepository = groupRepository;
        this.aigcEditRepository = aigcEditRepository;
        this.aigcStorageService = aigcStorageService;
    }

    @Transactional
    public UploadBatchResponse upload(String projectId, MultipartFile[] files) {
        Project project = requireProject(projectId);
        if (project.getStatus() == ProjectStatus.ANALYZING) {
            throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_IN_PROGRESS",
                    "Images cannot be uploaded during analysis");
        }
        if (files == null || files.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_FILES", "至少需要上传一张图片");
        }

        long currentCount = assetRepository.countByProjectId(projectId);
        List<AcceptedAsset> accepted = new ArrayList<>();
        List<DuplicateAsset> duplicates = new ArrayList<>();
        List<FailedAsset> failed = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalName = displayName(file);
            try {
                validateSize(file);
                String mimeType = detectMimeType(file);
                String sha256 = sha256(file);

                var existing = assetRepository.findByProjectIdAndSha256(projectId, sha256);
                if (existing.isPresent()) {
                    duplicates.add(new DuplicateAsset(originalName, sha256, existing.get().getId()));
                    continue;
                }
                if (currentCount + accepted.size() >= MAX_PROJECT_ASSETS) {
                    failed.add(new FailedAsset(
                            originalName,
                            "PROJECT_ASSET_LIMIT_EXCEEDED",
                            "项目最多保存 1000 张图片"));
                    continue;
                }

                String assetId = UUID.randomUUID().toString();
                AssetStorageService.StoredAsset stored =
                        storageService.store(projectId, assetId, file, mimeType);
                Instant now = Instant.now();
                Asset asset = new Asset(
                        assetId,
                        projectId,
                        originalName,
                        mimeType,
                        file.getSize(),
                        stored.width(),
                        stored.height(),
                        sha256,
                        stored.originalPath(),
                        stored.thumbnailPath(),
                        now);
                try {
                    assetRepository.save(asset);
                    accepted.add(AcceptedAsset.from(asset));
                } catch (RuntimeException exception) {
                    storageService.delete(stored.originalPath(), stored.thumbnailPath());
                    throw exception;
                }
            } catch (ApiException exception) {
                failed.add(new FailedAsset(originalName, exception.getCode(), exception.getMessage()));
            } catch (IOException | RuntimeException exception) {
                failed.add(new FailedAsset(originalName, "INVALID_IMAGE", "图片内容无效或无法读取"));
            }
        }

        if (accepted.isEmpty() && duplicates.isEmpty() && !failed.isEmpty()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UPLOAD_BATCH_FAILED",
                    "本批次没有成功上传的图片",
                    failed);
        }
        return new UploadBatchResponse(UUID.randomUUID().toString(), accepted, duplicates, failed);
    }

    @Transactional(readOnly = true)
    public PageResponse<AssetResponse> list(
            String projectId,
            int page,
            int pageSize,
            String decisionValue,
            String recommendationValue,
            String groupId,
            String analysisStatusValue,
            String sortValue) {
        requireProject(projectId);
        validatePage(page, pageSize);
        Pageable pageable = pageable(page, pageSize, sortValue);
        Page<Asset> result = assetRepository.search(
                projectId,
                parseDecision(decisionValue),
                parseRecommendation(recommendationValue),
                groupId,
                parseAnalysisStatus(analysisStatusValue),
                pageable);
        return new PageResponse<>(
                result.getContent().stream().map(AssetResponse::from).toList(),
                page,
                pageSize,
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AssetResponse get(String assetId) {
        Asset asset = findAsset(assetId);
        requireProject(asset.getProjectId());
        return AssetResponse.from(asset);
    }

    @Transactional
    public void delete(String assetId) {
        Asset asset = findAsset(assetId);
        Project project = requireProject(asset.getProjectId());
        if (project.getStatus() == ProjectStatus.DELETING) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_OPERATION_NOT_ALLOWED",
                    "Project is being deleted");
        }
        if (project.getStatus() == ProjectStatus.ANALYZING
                || jobRepository.findFirstByProjectIdAndTypeAndStatusInOrderByCreatedAtDesc(
                project.getId(), JobType.ANALYSIS, Set.of(JobStatus.QUEUED, JobStatus.RUNNING)).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_IN_PROGRESS",
                    "Images cannot be deleted during analysis");
        }
        if (exportTaskRepository.existsByProjectIdAndStatusIn(project.getId(),
                Set.of(ExportTask.Status.QUEUED, ExportTask.Status.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPORT_IN_PROGRESS",
                    "Images cannot be deleted during export");
        }
        if (aigcEditRepository.existsByAssetIdAndStatusIn(asset.getId(),
                Set.of(AigcEditStatus.QUEUED, AigcEditStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "AIGC_IN_PROGRESS",
                    "Image editing is still in progress");
        }

        String groupId = asset.getGroupId();
        List<AigcEdit> edits = aigcEditRepository.findAllByAssetId(asset.getId());
        List<String> generatedPaths = edits.stream()
                .map(AigcEdit::getGeneratedPath).filter(path -> path != null).toList();
        aigcEditRepository.deleteAllByAssetId(asset.getId());
        decisionHistoryRepository.deleteAllByAssetIdIn(Set.of(asset.getId()));
        assetRepository.delete(asset);
        assetRepository.flush();

        if (groupId != null) {
            int remaining = Math.toIntExact(assetRepository.countByProjectIdAndGroupId(project.getId(), groupId));
            groupRepository.findById(groupId).filter(group -> group.getProjectId().equals(project.getId()))
                    .ifPresent(group -> {
                        if (remaining == 0) {
                            groupRepository.delete(group);
                        } else {
                            group.removeAsset(asset.getId(), remaining);
                        }
                    });
        }
        if (assetRepository.countByProjectId(project.getId()) == 0) {
            project.setStatus(ProjectStatus.CREATED);
        }

        Runnable cleanup = () -> {
            storageService.delete(asset.getOriginalPath(), asset.getThumbnailPath());
            generatedPaths.forEach(aigcStorageService::delete);
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
        } else {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        }
    }

    @Transactional
    public AssetResponse updateDecision(String assetId, UpdateDecisionRequest request) {
        if (request == null || request.decision() == null || request.expectedVersion() == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "VALIDATION_ERROR",
                    "decision 和 expectedVersion 不能为空");
        }
        if (request.expectedVersion() < 0) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "VALIDATION_ERROR",
                    "expectedVersion 不能小于 0");
        }

        Asset asset = findAsset(assetId);
        requireProject(asset.getProjectId());
        if (asset.getVersion() != request.expectedVersion()) {
            throw versionConflict(asset);
        }

        AssetDecision previousDecision = asset.getDecision();
        asset.setDecision(request.decision());
        try {
            Asset saved = assetRepository.saveAndFlush(asset);
            decisionHistoryRepository.save(new DecisionHistory(
                    UUID.randomUUID().toString(),
                    saved.getId(),
                    currentUser.id(),
                    previousDecision,
                    saved.getDecision(),
                    "user",
                    Instant.now()));
            return AssetResponse.from(saved);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException exception) {
            throw versionConflict(asset);
        }
    }

    private ApiException versionConflict(Asset asset) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "图片已经被其他操作更新，请刷新后重试",
                java.util.Map.of("currentVersion", asset.getVersion()));
    }

    @Transactional(readOnly = true)
    public StoredFile getFile(String assetId, boolean thumbnail) {
        Asset asset = findAsset(assetId);
        requireProject(asset.getProjectId());
        String path = thumbnail ? asset.getThumbnailPath() : asset.getOriginalPath();
        return new StoredFile(
                storageService.load(path),
                thumbnail ? "image/jpeg" : asset.getMimeType(),
                thumbnail ? "thumbnail.jpg" : asset.getOriginalName());
    }

    private Asset findAsset(String assetId) {
        try {
            UUID.fromString(assetId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "图片不存在");
        }
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ASSET_NOT_FOUND",
                        "图片不存在"));
    }

    private Project requireProject(String projectId) {
        try {
            UUID.fromString(projectId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        }
        return projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, currentUser.id())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PROJECT_NOT_FOUND",
                        "项目不存在"));
    }

    private void validateSize(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_FILE", "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "单个文件不能超过 20 MB");
        }
    }

    private String detectMimeType(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(12);
            if (header.length >= 3
                    && (header[0] & 0xff) == 0xff
                    && (header[1] & 0xff) == 0xd8
                    && (header[2] & 0xff) == 0xff) {
                return "image/jpeg";
            }
            if (header.length >= 8
                    && header[0] == (byte) 0x89
                    && header[1] == 0x50
                    && header[2] == 0x4e
                    && header[3] == 0x47
                    && header[4] == 0x0d
                    && header[5] == 0x0a
                    && header[6] == 0x1a
                    && header[7] == 0x0a) {
                return "image/png";
            }
            if (header.length >= 12
                    && new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
                return "image/webp";
            }
        }
        throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_FILE_TYPE",
                "只支持 JPEG、PNG、WebP");
    }

    private String sha256(MultipartFile file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = file.getInputStream()) {
                input.transferTo(new DigestOutputStreamAdapter(digest));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String displayName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        return Path.of(name).getFileName().toString();
    }

    private Pageable pageable(int page, int pageSize, String sortValue) {
        String value = sortValue == null || sortValue.isBlank() ? "createdAt:desc" : sortValue;
        String[] parts = value.split(":", 2);
        String property = switch (parts[0]) {
            case "createdAt", "updatedAt", "sizeBytes", "originalName" -> parts[0];
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "图片排序字段不支持");
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

    private AssetDecision parseDecision(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return AssetDecision.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECISION", "decision 参数不合法");
        }
    }

    private AssetRecommendation parseRecommendation(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return AssetRecommendation.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RECOMMENDATION", "recommendation 参数不合法");
        }
    }

    private AnalysisStatus parseAnalysisStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return AnalysisStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANALYSIS_STATUS", "analysisStatus 参数不合法");
        }
    }

    public record StoredFile(Resource resource, String contentType, String filename) {
    }

    private static final class DigestOutputStreamAdapter extends java.io.OutputStream {
        private final MessageDigest digest;

        private DigestOutputStreamAdapter(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void write(int value) {
            digest.update((byte) value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            digest.update(bytes, offset, length);
        }
    }
}
