package com.cullpilot.backend.service.export;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.export.ExportRequests.CreateExportRequest;
import com.cullpilot.backend.api.export.ExportResponses.ExportResponse;
import com.cullpilot.backend.domain.asset.Asset;
import com.cullpilot.backend.domain.asset.AssetDecision;
import com.cullpilot.backend.domain.asset.DecisionHistory;
import com.cullpilot.backend.domain.aigc.AigcEdit;
import com.cullpilot.backend.domain.aigc.AigcEditStatus;
import com.cullpilot.backend.domain.export.ExportTask;
import com.cullpilot.backend.domain.group.AssetGroup;
import com.cullpilot.backend.domain.project.Project;
import com.cullpilot.backend.domain.project.ProjectSettings;
import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.asset.DecisionHistoryRepository;
import com.cullpilot.backend.repository.aigc.AigcEditRepository;
import com.cullpilot.backend.repository.export.ExportTaskRepository;
import com.cullpilot.backend.repository.group.AssetGroupRepository;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.security.CurrentUser;
import com.cullpilot.backend.service.asset.AssetStorageService;
import com.cullpilot.backend.service.asset.EffectiveDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportService {
    private static final String EXPORT_DIRECTORY = "cullpilot-export";

    private final ExportTaskRepository exportRepository;
    private final AssetGroupRepository groupRepository;
    private final ProjectRepository projectRepository;
    private final AssetRepository assetRepository;
    private final AigcEditRepository aigcEditRepository;
    private final DecisionHistoryRepository historyRepository;
    private final AssetStorageService storageService;
    private final ObjectMapper objectMapper;
    private final CurrentUser currentUser;
    private final Executor exportTaskExecutor;

    public ExportService(ExportTaskRepository exportRepository, AssetGroupRepository groupRepository,
                         ProjectRepository projectRepository,
                         AssetRepository assetRepository, AigcEditRepository aigcEditRepository,
                         DecisionHistoryRepository historyRepository,
                         AssetStorageService storageService, ObjectMapper objectMapper,
                         CurrentUser currentUser, @Qualifier("exportTaskExecutor") Executor exportTaskExecutor) {
        this.exportRepository = exportRepository;
        this.groupRepository = groupRepository;
        this.projectRepository = projectRepository;
        this.assetRepository = assetRepository;
        this.aigcEditRepository = aigcEditRepository;
        this.historyRepository = historyRepository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.currentUser = currentUser;
        this.exportTaskExecutor = exportTaskExecutor;
    }

    @Transactional
    public ExportResponse create(String projectId, CreateExportRequest request) {
        Project project = findProject(projectId);
        ExportOptions options = options(project, request);
        ExportTask task = new ExportTask(UUID.randomUUID().toString(), projectId, options.selection,
                options.copyImages, options.stripGps, options.includeManifest);
        ExportTask saved = exportRepository.save(task);
        scheduleAfterCommit(saved.getId());
        return ExportResponse.from(saved);
    }

    private void scheduleAfterCommit(String exportId) {
        Runnable action = () -> exportTaskExecutor.execute(() -> run(exportId));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    @Transactional(readOnly = true)
    public ExportResponse get(String exportId) {
        return ExportResponse.from(findOwned(exportId));
    }

    @Transactional(readOnly = true)
    public Resource download(String exportId) {
        ExportTask task = findOwned(exportId);
        if (task.getStatus() != ExportTask.Status.SUCCEEDED || task.getFilePath() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPORT_NOT_READY", "导出任务尚未完成");
        }
        return storageService.loadExport(task.getFilePath());
    }

    public void run(String exportId) {
        ExportTask task = exportRepository.findById(exportId).orElse(null);
        if (task == null || task.getStatus() != ExportTask.Status.QUEUED) return;
        try {
            task.setStatus(ExportTask.Status.RUNNING);
            task = exportRepository.saveAndFlush(task);
            Project project = projectRepository.findById(task.getProjectId()).orElseThrow();
            SelectionResult selection = selectAssets(task, project.getId());
            Map<String, String> groupDirectories = groupDirectories(project.getId());
            task.setTotalCount(selection.assets().size() + selection.edits().size());
            task = exportRepository.saveAndFlush(task);

            Path exportRoot = storageService.exportPath(project.getId(), task.getId());
            Path zipPath = exportRoot.resolve("export.zip");
            writeZip(zipPath, task, selection.assets(), selection.userOperatedAssetIds(),
                    groupDirectories, selection.edits());
            task = exportRepository.findById(exportId).orElseThrow();
            task.setFilePath(relativePath(project.getId(), task.getId()));
            task.setStatus(ExportTask.Status.SUCCEEDED);
            task.setFinishedAt(Instant.now());
            task.setProcessedCount(selection.assets().size() + selection.edits().size());
            exportRepository.saveAndFlush(task);
        } catch (Exception exception) {
            task = exportRepository.findById(exportId).orElse(task);
            task.setStatus(ExportTask.Status.FAILED);
            task.setErrorMessage(exception.getMessage() == null ? "导出失败" : exception.getMessage());
            task.setFinishedAt(Instant.now());
            exportRepository.saveAndFlush(task);
        }
    }

    private SelectionResult selectAssets(ExportTask task, String projectId) {
        List<Asset> assets = assetRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId);
        Set<String> userOperated = new HashSet<>(historyRepository.findAllByAssetIdIn(
                        assets.stream().map(Asset::getId).toList()).stream()
                .map(DecisionHistory::getAssetId).toList());
        List<Asset> selected = new ArrayList<>();
        for (Asset asset : assets) {
            AssetDecision effective = EffectiveDecision.of(asset, userOperated);
            boolean include = effective == AssetDecision.KEEP
                    || (task.getSelection() == ExportTask.Selection.KEEP_AND_REVIEW
                    && effective == AssetDecision.REVIEW);
            if (include) selected.add(asset);
        }
        Set<String> selectedIds = selected.stream().map(Asset::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<AigcEdit> generated = aigcEditRepository
                .findAllByProjectIdAndStatusInOrderByCreatedAtAsc(projectId, List.of(AigcEditStatus.SUCCEEDED))
                .stream().filter(edit -> selectedIds.contains(edit.getAssetId())).toList();
        return new SelectionResult(selected, userOperated, generated);
    }

    private void writeZip(Path zipPath, ExportTask task, List<Asset> assets,
                          Set<String> userOperatedAssetIds,
                          Map<String, String> groupDirectories, List<AigcEdit> edits) throws IOException {
        try (OutputStream output = Files.newOutputStream(zipPath);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            List<String> manifest = new ArrayList<>();
            manifest.add("assetId,originalName,effectiveDecision,decisionSource,userDecision,recommendation,path,itemType,aigcEditId,prompt");
            zip.putNextEntry(new ZipEntry(EXPORT_DIRECTORY + "/"));
            zip.closeEntry();
            Set<String> createdDirectories = new HashSet<>();
            int index = 0;
            for (Asset asset : assets) {
                String groupDirectory = groupDirectories.getOrDefault(asset.getGroupId(), "ungrouped");
                String directory = EXPORT_DIRECTORY + "/" + groupDirectory;
                if (createdDirectories.add(directory)) {
                    zip.putNextEntry(new ZipEntry(directory + "/"));
                    zip.closeEntry();
                }
                String filename = directory + "/" + asset.getId() + "-" + safeFilename(asset.getOriginalName());
                if (task.isCopyImages()) {
                    Path temporary = storageService.exportPath(task.getProjectId(), task.getId())
                            .resolve("tmp").resolve(safeFilename(asset.getId() + "-" + asset.getOriginalName()));
                    storageService.copyForExport(asset.getOriginalPath(), temporary, asset.getMimeType(), task.isStripGps());
                    zip.putNextEntry(new ZipEntry(filename));
                    Files.copy(temporary, zip);
                    zip.closeEntry();
                    Files.deleteIfExists(temporary);
                }
                manifest.add(csv(asset.getId()) + "," + csv(asset.getOriginalName()) + ","
                        + csv(effectiveDecision(asset, userOperatedAssetIds)) + ","
                        + csv(userOperatedAssetIds.contains(asset.getId()) ? "user"
                                : asset.getRecommendation() == null ? "default" : "ai") + ","
                        + csv(userOperatedAssetIds.contains(asset.getId()) ? asset.getDecision().value() : "") + ","
                        + csv(asset.getRecommendation() == null ? "" : asset.getRecommendation().value()) + ","
                        + csv(task.isCopyImages() ? filename : "") + ",original,,");
                index++;
                updateProgress(task.getId(), index);
            }
            if (task.isCopyImages() && !edits.isEmpty()) {
                zip.putNextEntry(new ZipEntry(EXPORT_DIRECTORY + "/aigc/"));
                zip.closeEntry();
            }
            Map<String, Asset> assetsById = new HashMap<>();
            assets.forEach(asset -> assetsById.put(asset.getId(), asset));
            for (AigcEdit edit : edits) {
                Asset source = assetsById.get(edit.getAssetId());
                if (source == null) continue;
                String filename = aigcFilename(edit, source);
                if (task.isCopyImages()) {
                    Path temporary = storageService.exportPath(task.getProjectId(), task.getId())
                            .resolve("tmp").resolve(Path.of(filename).getFileName().toString());
                    storageService.copyForExport(edit.getGeneratedPath(), temporary,
                            edit.getMimeType(), task.isStripGps());
                    zip.putNextEntry(new ZipEntry(filename));
                    Files.copy(temporary, zip);
                    zip.closeEntry();
                    Files.deleteIfExists(temporary);
                }
                manifest.add(csv(source.getId()) + "," + csv(source.getOriginalName()) + ","
                        + csv(effectiveDecision(source, userOperatedAssetIds)) + ","
                        + csv(userOperatedAssetIds.contains(source.getId()) ? "user"
                                : source.getRecommendation() == null ? "default" : "ai") + ","
                        + csv(userOperatedAssetIds.contains(source.getId()) ? source.getDecision().value() : "") + ","
                        + csv(source.getRecommendation() == null ? "" : source.getRecommendation().value()) + ","
                        + csv(task.isCopyImages() ? filename : "") + ",aigc," + csv(edit.getId()) + ","
                        + csv(edit.getPromptUsed()));
                index++;
                updateProgress(task.getId(), index);
            }
            if (task.isIncludeManifest()) {
                zip.putNextEntry(new ZipEntry(EXPORT_DIRECTORY + "/manifest.csv"));
                zip.write(String.join("\n", manifest).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private Map<String, String> groupDirectories(String projectId) {
        Map<String, String> directories = new HashMap<>();
        for (AssetGroup group : groupRepository.findAllByProjectIdOrderByGroupNoAsc(projectId)) {
            directories.put(group.getId(), String.format(Locale.ROOT, "group-%03d", group.getGroupNo()));
        }
        return directories;
    }

    private String effectiveDecision(Asset asset, Set<String> userOperatedAssetIds) {
        return EffectiveDecision.of(asset, userOperatedAssetIds).value();
    }

    private void updateProgress(String exportId, int processed) {
        exportRepository.findById(exportId).ifPresent(task -> {
            task.setProcessedCount(processed);
            exportRepository.saveAndFlush(task);
        });
    }

    private ExportOptions options(Project project, CreateExportRequest request) {
        if (request == null || request.selection() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "selection 不能为空");
        }
        ExportTask.Selection selection = switch (request.selection().trim().toLowerCase(Locale.ROOT)) {
            case "keep" -> ExportTask.Selection.KEEP;
            case "keepandreview" -> ExportTask.Selection.KEEP_AND_REVIEW;
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EXPORT_SELECTION", "selection 必须是 keep 或 keepAndReview");
        };
        if (request.manifestFormat() != null && !request.manifestFormat().equalsIgnoreCase("csv")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MANIFEST_FORMAT", "manifestFormat 目前只支持 csv");
        }
        ProjectSettings settings = readSettings(project);
        return new ExportOptions(selection, request.copyImages() == null || request.copyImages(),
                request.stripGps() == null ? settings.getPrivacy().isStripGpsOnExport() : request.stripGps(),
                request.includeManifest() == null || request.includeManifest());
    }

    private ProjectSettings readSettings(Project project) {
        try {
            return objectMapper.readValue(project.getSettingsJson(), ProjectSettings.class);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PROJECT_SETTINGS_CORRUPTED", "项目设置数据损坏");
        }
    }

    private Project findProject(String projectId) {
        try { UUID.fromString(projectId); } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        }
        return projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, currentUser.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"));
    }

    private ExportTask findOwned(String exportId) {
        try { UUID.fromString(exportId); } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "导出任务不存在");
        }
        ExportTask task = exportRepository.findById(exportId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "导出任务不存在"));
        findProject(task.getProjectId());
        return task;
    }

    private String relativePath(String projectId, String exportId) {
        return projectId + "/exports/" + exportId + "/export.zip";
    }

    private String safeFilename(String value) {
        String filename = Path.of(value == null ? "image" : value).getFileName().toString();
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String aigcFilename(AigcEdit edit, Asset source) {
        String originalName = Path.of(source.getOriginalName() == null ? "image" : source.getOriginalName())
                .getFileName().toString();
        int extensionStart = originalName.lastIndexOf('.');
        String baseName = extensionStart > 0 ? originalName.substring(0, extensionStart) : originalName;
        return EXPORT_DIRECTORY + "/aigc/"
                + safeFilename(edit.getId() + "-" + baseName + generatedExtension(edit.getMimeType()));
    }

    private String generatedExtension(String mimeType) {
        return switch (mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new IllegalStateException("Unsupported generated image type");
        };
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        safe = safe.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private record ExportOptions(ExportTask.Selection selection, boolean copyImages,
                                 boolean stripGps, boolean includeManifest) {}

    private record SelectionResult(List<Asset> assets, Set<String> userOperatedAssetIds,
                                   List<AigcEdit> edits) {}
}
