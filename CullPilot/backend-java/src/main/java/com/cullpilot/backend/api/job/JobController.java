package com.cullpilot.backend.api.job;

import com.cullpilot.backend.api.ApiResponse;
import com.cullpilot.backend.api.job.JobRequests.AnalyzeRequest;
import com.cullpilot.backend.api.job.JobResponses.JobResponse;
import com.cullpilot.backend.api.job.JobResponses.ProjectSummaryResponse;
import com.cullpilot.backend.service.job.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/api/v1/projects/{projectId}/analyze")
    public ResponseEntity<ApiResponse<JobResponse>> analyze(
            @PathVariable String projectId,
            @RequestBody(required = false) AnalyzeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(jobService.startAnalysis(projectId, request, idempotencyKey)));
    }

    @GetMapping("/api/v1/jobs/{jobId}")
    public ResponseEntity<ApiResponse<JobResponse>> get(@PathVariable String jobId) {
        return ResponseEntity.ok(ApiResponse.success(jobService.get(jobId)));
    }

    @PostMapping("/api/v1/jobs/{jobId}/cancel")
    public ResponseEntity<ApiResponse<JobResponse>> cancel(@PathVariable String jobId) {
        return ResponseEntity.ok(ApiResponse.success(jobService.cancel(jobId)));
    }

    @PostMapping("/api/v1/jobs/{jobId}/retry")
    public ResponseEntity<ApiResponse<JobResponse>> retry(@PathVariable String jobId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(jobService.retry(jobId)));
    }

    @GetMapping("/api/v1/projects/{projectId}/summary")
    public ResponseEntity<ApiResponse<ProjectSummaryResponse>> summary(@PathVariable String projectId) {
        return ResponseEntity.ok(ApiResponse.success(jobService.summary(projectId)));
    }
}
