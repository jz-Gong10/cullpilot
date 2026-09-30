package com.cullpilot.backend;

import com.cullpilot.backend.integration.InstructionContract;
import com.cullpilot.backend.integration.PythonApiClient;
import com.cullpilot.backend.repository.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "instruction-test-user")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/cullpilot-instruction-tests.db",
        "storage.root=./target/cullpilot-instruction-test-storage"
})
class InstructionControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projects;
    @MockitoBean private PythonApiClient python;

    @BeforeEach
    void cleanDatabase() {
        projects.deleteAll();
    }

    @Test
    void parsesRequirementsThroughPythonAdapter() throws Exception {
        String projectId = createProject();
        when(python.parseInstruction(any())).thenReturn(new InstructionContract.Response(
                new InstructionContract.Strategy(3, "standard", "auto", Map.of(), Map.of(), List.of(), "ok"),
                0.8, false, "builtin", "rule-based-v1"));

        mockMvc.perform(post("/api/v1/projects/{projectId}/parse-instruction", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"clear photos\",\"allowedFeatures\":[\"sharpness\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategy.keepPerGroup").value(3));
        verify(python).parseInstruction(new InstructionContract.Request("clear photos", List.of("sharpness")));
    }

    @Test
    void reportsUnavailablePythonService() throws Exception {
        String projectId = createProject();
        when(python.parseInstruction(any())).thenThrow(new ResourceAccessException("connection refused"));

        mockMvc.perform(post("/api/v1/projects/{projectId}/parse-instruction", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("INSTRUCTION_PARSER_UNAVAILABLE"));
    }

    private String createProject() throws Exception {
        String response = mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Instruction test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return response.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }
}
