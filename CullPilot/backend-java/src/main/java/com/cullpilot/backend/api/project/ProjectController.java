package com.cullpilot.backend.api.project;

import com.cullpilot.backend.api.ApiResponse;
import com.cullpilot.backend.api.project.ProjectRequests.CreateProjectRequest;
import com.cullpilot.backend.api.project.ProjectRequests.ProjectSettingsPatch;
import com.cullpilot.backend.api.project.ProjectResponses.DeleteProjectResponse;
import com.cullpilot.backend.api.project.ProjectResponses.PageResponse;
import com.cullpilot.backend.api.project.ProjectResponses.ProjectResponse;
import com.cullpilot.backend.service.project.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> create(
            @Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(projectService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProjectResponse>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(ApiResponse.success(
                projectService.list(status, page, pageSize, sort)));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectResponse>> get(@PathVariable String projectId) {
        return ResponseEntity.ok(ApiResponse.success(projectService.get(projectId)));
    }

    @PatchMapping("/{projectId}/settings")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateSettings(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectSettingsPatch patch) {
        return ResponseEntity.ok(ApiResponse.success(
                projectService.updateSettings(projectId, patch)));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<ApiResponse<DeleteProjectResponse>> delete(@PathVariable String projectId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(projectService.delete(projectId)));
    }
}
