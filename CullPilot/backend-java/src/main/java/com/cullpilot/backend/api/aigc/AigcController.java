package com.cullpilot.backend.api.aigc;

import com.cullpilot.backend.api.ApiResponse;
import com.cullpilot.backend.api.aigc.AigcRequests.CreateEditsRequest;
import com.cullpilot.backend.api.aigc.AigcResponses.BatchResponse;
import com.cullpilot.backend.api.aigc.AigcResponses.EditResponse;
import com.cullpilot.backend.api.aigc.AigcResponses.GroupResponse;
import com.cullpilot.backend.service.aigc.AigcService;
import com.cullpilot.backend.service.asset.AssetService.StoredFile;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
public class AigcController {
    private final AigcService service;

    public AigcController(AigcService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/projects/{projectId}/aigc-edits")
    public ResponseEntity<ApiResponse<BatchResponse>> create(
            @PathVariable String projectId, @Valid @RequestBody CreateEditsRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(service.create(projectId, request)));
    }

    @GetMapping("/api/v1/projects/{projectId}/aigc-edits")
    public ApiResponse<GroupResponse> list(@PathVariable String projectId) {
        return ApiResponse.success(service.list(projectId));
    }

    @GetMapping("/api/v1/aigc-edits/{editId}")
    public ApiResponse<EditResponse> get(@PathVariable String editId) {
        return ApiResponse.success(service.get(editId));
    }

    @GetMapping("/api/v1/aigc-edits/{editId}/image")
    public ResponseEntity<Resource> image(@PathVariable String editId) {
        StoredFile file = service.image(editId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.resource());
    }
}
