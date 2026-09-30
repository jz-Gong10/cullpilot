package com.cullpilot.backend.service.instruction;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.instruction.InstructionRequests.ParseInstructionRequest;
import com.cullpilot.backend.api.instruction.InstructionResponses.ParseInstructionResponse;
import com.cullpilot.backend.api.instruction.InstructionResponses.SelectionStrategy;
import com.cullpilot.backend.integration.InstructionContract;
import com.cullpilot.backend.integration.PythonApiClient;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Service
public class InstructionService {
    private static final Logger log = LoggerFactory.getLogger(InstructionService.class);
    private final ProjectRepository projects;
    private final CurrentUser currentUser;
    private final PythonApiClient python;

    public InstructionService(ProjectRepository projects, CurrentUser currentUser, PythonApiClient python) {
        this.projects = projects;
        this.currentUser = currentUser;
        this.python = python;
    }

    public ParseInstructionResponse parse(String projectId, ParseInstructionRequest request) {
        requireProject(projectId);
        String text = request == null || request.text() == null ? "" : request.text().trim();
        List<String> allowed = request == null || request.allowedFeatures() == null
                ? List.of() : request.allowedFeatures();
        InstructionContract.Response parsed;
        try {
            parsed = python.parseInstruction(new InstructionContract.Request(text, allowed));
        } catch (RestClientException exception) {
            log.warn("Python instruction parser request failed: {}", exception.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INSTRUCTION_PARSER_UNAVAILABLE",
                    "筛选要求解析服务暂时不可用");
        }
        if (parsed == null || parsed.strategy() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_INSTRUCTION_RESPONSE",
                    "筛选要求解析服务返回了无效结果");
        }
        var strategy = parsed.strategy();
        return new ParseInstructionResponse(
                text,
                new SelectionStrategy(strategy.keepPerGroup(), strategy.strictness(), strategy.contentMode(),
                        strategy.weights(), strategy.constraints()),
                parsed.confidence(),
                strategy.unsupportedTerms() == null ? List.of() : strategy.unsupportedTerms(),
                strategy.explanation(), parsed.fallbackUsed(), parsed.provider(), parsed.model());
    }

    private void requireProject(String projectId) {
        try {
            java.util.UUID.fromString(projectId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        }
        projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, currentUser.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"));
    }
}
