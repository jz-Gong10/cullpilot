package com.cullpilot.backend.api.export;

import com.cullpilot.backend.api.ApiResponse;
import com.cullpilot.backend.api.export.ExportRequests.CreateExportRequest;
import com.cullpilot.backend.api.export.ExportResponses.ExportResponse;
import com.cullpilot.backend.service.export.ExportService;
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

@RestController
public class ExportController {
    private final ExportService exportService;

    public ExportController(ExportService exportService) { this.exportService = exportService; }

    @PostMapping("/api/v1/projects/{projectId}/export")
    public ResponseEntity<ApiResponse<ExportResponse>> create(
            @PathVariable String projectId, @RequestBody CreateExportRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(exportService.create(projectId, request)));
    }

    @GetMapping("/api/v1/exports/{exportId}")
    public ApiResponse<ExportResponse> get(@PathVariable String exportId) {
        return ApiResponse.success(exportService.get(exportId));
    }

    @GetMapping("/api/v1/exports/{exportId}/download")
    public ResponseEntity<Resource> download(@PathVariable String exportId) {
        Resource file = exportService.download(exportId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("cullpilot-export-" + exportId + ".zip").build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(file);
    }
}
