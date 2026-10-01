package com.cullpilot.backend.integration;

import com.cullpilot.backend.integration.AnalysisContract.Request;
import com.cullpilot.backend.integration.AnalysisContract.Result;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Entry point for calls from the Java business layer to the Python API adapter.
 */
public class PythonApiClient {

    private final RestClient restClient;

    public PythonApiClient(RestClient pythonApiRestClient) {
        this.restClient = pythonApiRestClient;
    }

    protected RestClient restClient() {
        return restClient;
    }

    public Result analyze(Request request) {
        Result result = restClient.post()
                .uri("/api/v1/internal/analyze")
                .body(request)
                .retrieve()
                .body(Result.class);
        if (result == null) {
            throw new IllegalStateException("Python analysis returned an empty response");
        }
        return result;
    }

    public InstructionContract.Response parseInstruction(InstructionContract.Request request) {
        InstructionContract.Response result = restClient.post()
                .uri("/api/v1/internal/llm/parse-instruction")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(InstructionContract.Response.class);
        if (result == null) {
            throw new IllegalStateException("Python instruction parser returned an empty response");
        }
        return result;
    }

    public AigcContract.Result editImage(AigcContract.Request request) {
        AigcContract.Result result = restClient.post()
                .uri("/api/v1/internal/aigc/image-edit")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AigcContract.Result.class);
        if (result == null) {
            throw new IllegalStateException("Python image editor returned an empty response");
        }
        return result;
    }
}
