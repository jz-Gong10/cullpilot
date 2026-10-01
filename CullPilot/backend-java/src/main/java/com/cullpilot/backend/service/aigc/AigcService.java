package com.cullpilot.backend.service.aigc;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.aigc.AigcRequests.CreateEditsRequest;
import com.cullpilot.backend.api.aigc.AigcResponses.BatchResponse;
import com.cullpilot.backend.api.aigc.AigcResponses.EditResponse;
import com.cullpilot.backend.api.aigc.AigcResponses.GroupResponse;
import com.cullpilot.backend.domain.aigc.AigcEdit;
import com.cullpilot.backend.domain.aigc.AigcEditStatus;
import com.cullpilot.backend.domain.asset.Asset;
import com.cullpilot.backend.domain.asset.AssetDecision;
import com.cullpilot.backend.integration.AigcContract;
import com.cullpilot.backend.integration.PythonApiClient;
import com.cullpilot.backend.repository.aigc.AigcEditRepository;
import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.asset.DecisionHistoryRepository;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.security.CurrentUser;
import com.cullpilot.backend.service.asset.AssetService.StoredFile;
import com.cullpilot.backend.service.asset.AssetStorageService;
import com.cullpilot.backend.service.asset.EffectiveDecision;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Service
public class AigcService {
    private static final String DEFAULT_MODEL = "qwen-image-3.0";
    private static final String IMAGE_SIZE = "1024*1024";
    private static final Pattern IMAGE_SIZE_PATTERN = Pattern.compile("\\d{3,4}\\*\\d{3,4}");
    private final AigcEditRepository edits;
    private final AssetRepository assets;
    private final ProjectRepository projects;
    private final DecisionHistoryRepository histories;
    private final CurrentUser currentUser;
    private final PythonApiClient pythonApi;
    private final AssetStorageService assetStorage;
    private final AigcStorageService aigcStorage;
    private final Executor executor;
    private final TransactionTemplate transaction;

    public AigcService(AigcEditRepository edits, AssetRepository assets, ProjectRepository projects,
                       DecisionHistoryRepository histories, CurrentUser currentUser,
                       PythonApiClient pythonApi, AssetStorageService assetStorage,
                       AigcStorageService aigcStorage, PlatformTransactionManager transactionManager,
                       @Qualifier("aigcTaskExecutor") Executor executor) {
        this.edits = edits;
        this.assets = assets;
        this.projects = projects;
        this.histories = histories;
        this.currentUser = currentUser;
        this.pythonApi = pythonApi;
        this.assetStorage = assetStorage;
        this.aigcStorage = aigcStorage;
        this.executor = executor;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public BatchResponse create(String projectId, CreateEditsRequest request) {
        requireProject(projectId);
        String model = request.model() == null || request.model().isBlank()
                ? DEFAULT_MODEL : request.model().trim();
        String size = request.size() == null || request.size().isBlank()
                ? IMAGE_SIZE : request.size().trim();
        if (!IMAGE_SIZE_PATTERN.matcher(size).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IMAGE_SIZE", "size must look like 1024*1024");
        }
        boolean promptExtend = request.promptExtend() == null || request.promptExtend();
        boolean watermark = Boolean.TRUE.equals(request.watermark());
        Set<String> uniqueAssetIds = new LinkedHashSet<>();
        for (var item : request.items()) {
            validateUuid(item.assetId(), "ASSET_NOT_FOUND");
            if (!uniqueAssetIds.add(item.assetId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_ASSET", "Each image may appear only once in a batch");
            }
        }

        Set<String> userOperated = new HashSet<>(histories.findAllByAssetIdIn(uniqueAssetIds).stream()
                .map(history -> history.getAssetId()).toList());
        List<AigcEdit> saved = new ArrayList<>();
        Instant now = Instant.now();
        for (var item : request.items()) {
            Asset asset = assets.findByIdAndProjectId(item.assetId(), projectId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "Image not found in project"));
            if (!eligibleForEdit(asset, userOperated)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ASSET_NOT_ELIGIBLE_FOR_AIGC",
                        "Only keep or review images can be edited");
            }
            String prompt = item.prompt() == null ? "" : item.prompt().trim();
            if (prompt.length() > 2000) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROMPT_TOO_LONG", "Prompt must be 2000 characters or fewer");
            }
            saved.add(edits.save(new AigcEdit(UUID.randomUUID().toString(), projectId, asset.getId(),
                    prompt, model, size, promptExtend, watermark, now)));
        }
        schedule(saved.stream().map(AigcEdit::getId).toList());
        String groupId = groupId(projectId);
        return new BatchResponse(groupId, saved.stream().map(EditResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public GroupResponse list(String projectId) {
        requireProject(projectId);
        List<EditResponse> items = edits.findAllByProjectIdOrderByCreatedAtDesc(projectId)
                .stream().map(EditResponse::from).toList();
        int imageCount = (int) items.stream().filter(item -> "succeeded".equals(item.status())).count();
        return new GroupResponse(groupId(projectId), projectId, "aigc", imageCount, items);
    }

    @Transactional(readOnly = true)
    public EditResponse get(String editId) {
        AigcEdit edit = findEdit(editId);
        requireProject(edit.getProjectId());
        return EditResponse.from(edit);
    }

    @Transactional(readOnly = true)
    public StoredFile image(String editId) {
        AigcEdit edit = findEdit(editId);
        requireProject(edit.getProjectId());
        if (edit.getStatus() != AigcEditStatus.SUCCEEDED || edit.getGeneratedPath() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "AIGC_IMAGE_NOT_READY", "Generated image is not ready");
        }
        Resource resource = assetStorage.load(edit.getGeneratedPath());
        String filename = Path.of(edit.getGeneratedPath()).getFileName().toString();
        return new StoredFile(resource, edit.getMimeType(), filename);
    }

    private void schedule(List<String> ids) {
        Runnable action = () -> ids.forEach(id -> executor.execute(() -> run(id)));
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

    private void run(String editId) {
        AigcEdit edit = transaction.execute(status -> {
            AigcEdit current = edits.findById(editId).orElse(null);
            if (current == null || current.getStatus() != AigcEditStatus.QUEUED) return null;
            current.markRunning(Instant.now());
            return edits.saveAndFlush(current);
        });
        if (edit == null) return;
        String storedPath = null;
        try {
            Asset asset = assets.findByIdAndProjectId(edit.getAssetId(), edit.getProjectId()).orElseThrow();
            Set<String> userOperated = new HashSet<>(histories.findAllByAssetIdIn(Set.of(asset.getId())).stream()
                    .map(history -> history.getAssetId()).toList());
            if (!eligibleForEdit(asset, userOperated)) {
                throw new IllegalStateException("AIGC source image is no longer eligible for editing");
            }
            AigcContract.Result result = pythonApi.editImage(new AigcContract.Request(
                    asset.getOriginalPath(), edit.getPrompt(),
                    DEFAULT_MODEL.equals(edit.getModel()) ? null : edit.getModel(), edit.getSize(),
                    edit.isPromptExtend(), edit.isWatermark()));
            if (result.imageBase64() == null || result.promptUsed() == null || result.provider() == null) {
                throw new IllegalStateException("Python image editor returned an incomplete result");
            }
            AigcStorageService.StoredImage stored = aigcStorage.store(edit.getProjectId(), edit.getId(),
                    result.imageBase64(), result.mimeType());
            storedPath = stored.path();
            String finalStoredPath = storedPath;
            transaction.executeWithoutResult(status -> {
                AigcEdit current = edits.findById(editId).orElseThrow();
                current.markSucceeded(result.promptUsed(), result.provider(), result.model(), finalStoredPath,
                        result.mimeType(), stored.sizeBytes(), Instant.now());
                edits.saveAndFlush(current);
            });
        } catch (Exception exception) {
            if (storedPath != null) aigcStorage.delete(storedPath);
            String failureMessage = exception.getMessage() == null ? "AIGC image edit failed" : exception.getMessage();
            if (failureMessage.length() > 500) failureMessage = failureMessage.substring(0, 500);
            String message = failureMessage;
            transaction.executeWithoutResult(status -> edits.findById(editId).ifPresent(current -> {
                current.markFailed(message, Instant.now());
                edits.saveAndFlush(current);
            }));
        }
    }

    private AigcEdit findEdit(String id) {
        validateUuid(id, "AIGC_EDIT_NOT_FOUND");
        return edits.findById(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "AIGC_EDIT_NOT_FOUND", "AIGC edit not found"));
    }

    private boolean eligibleForEdit(Asset asset, Set<String> userOperated) {
        if (!userOperated.contains(asset.getId()) && asset.getRecommendation() == null) return false;
        AssetDecision effective = EffectiveDecision.of(asset, userOperated);
        return effective == AssetDecision.KEEP || effective == AssetDecision.REVIEW;
    }

    private void requireProject(String projectId) {
        validateUuid(projectId, "PROJECT_NOT_FOUND");
        projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, currentUser.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
    }

    private void validateUuid(String value, String code) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, code, "Resource not found");
        }
    }

    private String groupId(String projectId) {
        return "aigc-" + projectId;
    }
}
