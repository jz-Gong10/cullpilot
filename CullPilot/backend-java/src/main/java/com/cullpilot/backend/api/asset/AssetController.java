package com.cullpilot.backend.api.asset;

import com.cullpilot.backend.api.ApiResponse;
import com.cullpilot.backend.api.asset.AssetResponses.AssetResponse;
import com.cullpilot.backend.api.asset.AssetResponses.PageResponse;
import com.cullpilot.backend.api.asset.AssetResponses.UploadBatchResponse;
import com.cullpilot.backend.api.asset.AssetRequests.UpdateDecisionRequest;
import com.cullpilot.backend.domain.asset.AssetDecision;
import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.domain.asset.AnalysisStatus;
import com.cullpilot.backend.service.asset.AssetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping(
            value = "/api/v1/projects/{projectId}/assets",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UploadBatchResponse>> upload(
            @PathVariable String projectId,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "file", required = false) MultipartFile[] file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        MultipartFile[] uploadFiles = mergeFiles(files, file);
        return ResponseEntity.status(201)
                .body(ApiResponse.success(assetService.upload(projectId, uploadFiles)));
    }

    private MultipartFile[] mergeFiles(MultipartFile[] files, MultipartFile[] file) {
        int first = files == null ? 0 : files.length;
        int second = file == null ? 0 : file.length;
        MultipartFile[] merged = new MultipartFile[first + second];
        if (first > 0) System.arraycopy(files, 0, merged, 0, first);
        if (second > 0) System.arraycopy(file, 0, merged, first, second);
        return merged;
    }

    @GetMapping("/api/v1/projects/{projectId}/assets")
    public ResponseEntity<ApiResponse<PageResponse<AssetResponse>>> list(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String recommendation,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String analysisStatus,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(ApiResponse.success(assetService.list(
                projectId,
                page,
                pageSize,
                decision,
                recommendation,
                groupId,
                analysisStatus,
                sort)));
    }

    @GetMapping("/api/v1/assets/{assetId}")
    public ResponseEntity<ApiResponse<AssetResponse>> get(@PathVariable String assetId) {
        return ResponseEntity.ok(ApiResponse.success(assetService.get(assetId)));
    }

    @PatchMapping(value = "/api/v1/assets/{assetId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<AssetResponse>> updateDecision(
            @PathVariable String assetId,
            @RequestBody UpdateDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(assetService.updateDecision(assetId, request)));
    }

    @GetMapping("/api/v1/assets/{assetId}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable String assetId) {
        return file(assetService.getFile(assetId, true));
    }

    @GetMapping("/api/v1/assets/{assetId}/original")
    public ResponseEntity<Resource> original(@PathVariable String assetId) {
        return file(assetService.getFile(assetId, false));
    }

    private ResponseEntity<Resource> file(AssetService.StoredFile storedFile) {
        MediaType mediaType = MediaType.parseMediaType(storedFile.contentType());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(storedFile.filename(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(storedFile.resource());
    }
}
