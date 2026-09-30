package com.cullpilot.backend.api.instruction;

import com.cullpilot.backend.api.ApiResponse;
import com.cullpilot.backend.api.instruction.InstructionRequests.ParseInstructionRequest;
import com.cullpilot.backend.api.instruction.InstructionResponses.ParseInstructionResponse;
import com.cullpilot.backend.service.instruction.InstructionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InstructionController {
    private final InstructionService instructionService;

    public InstructionController(InstructionService instructionService) {
        this.instructionService = instructionService;
    }

    @PostMapping("/api/v1/projects/{projectId}/parse-instruction")
    public ResponseEntity<ApiResponse<ParseInstructionResponse>> parse(
            @PathVariable String projectId,
            @Valid @RequestBody(required = false) ParseInstructionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(instructionService.parse(projectId, request)));
    }
}
