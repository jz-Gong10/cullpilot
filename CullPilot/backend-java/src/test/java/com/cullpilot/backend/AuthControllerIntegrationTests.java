package com.cullpilot.backend;

import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.job.JobErrorRepository;
import com.cullpilot.backend.repository.job.JobRepository;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.repository.user.UserRepository;
import com.cullpilot.backend.repository.user.UserSessionRepository;
import com.cullpilot.backend.domain.asset.Asset;
import com.cullpilot.backend.domain.job.Job;
import com.cullpilot.backend.domain.job.JobType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/cullpilot-auth-tests.db",
        "storage.root=./target/cullpilot-auth-test-storage"
})
class AuthControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobErrorRepository jobErrorRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jobErrorRepository.deleteAll();
        jobRepository.deleteAll();
        assetRepository.deleteAll();
        projectRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registersLogsInAndRevokesToken() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        JsonNode registered = register("User@Example.com", "Password123!", "Photo Owner");
        String token = registered.path("accessToken").asText();
        String userId = registered.path("user").path("id").asText();
        assertThat(registered.path("user").path("email").asText()).isEqualTo("user@example.com");
        assertThat(registered.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(registered.path("expiresAt").asText()).isNotBlank();
        assertThat(userRepository.findById(userId).orElseThrow().getPasswordHash())
                .isNotEqualTo("Password123!")
                .startsWith("$2");
        assertThat(sessionRepository.findAll().get(0).getTokenHash()).doesNotContain(token);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId));

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"USER@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(userId))
                .andReturn().getResponse().getContentAsString();
        String secondToken = objectMapper.readTree(loginResponse)
                .path("data").path("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(secondToken)))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE user_sessions SET expires_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), userId);
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(secondToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDuplicateEmailAndBadCredentials() throws Exception {
        register("alice@example.com", "Password123!", null);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ALICE@example.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad-address\",\"password\":\"short\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void isolatesProjectsAndJobsBetweenUsers() throws Exception {
        String alice = register("alice@example.com", "Password123!", null)
                .path("accessToken").asText();
        String bob = register("bob@example.com", "Password123!", null)
                .path("accessToken").asText();

        String response = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Private photos\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String projectId = objectMapper.readTree(response).path("data").path("id").asText();

        String assetId = UUID.randomUUID().toString();
        assetRepository.save(new Asset(
                assetId,
                projectId,
                "private.png",
                "image/png",
                10,
                1,
                1,
                "a".repeat(64),
                projectId + "/assets/" + assetId + "/original.png",
                projectId + "/assets/" + assetId + "/thumbnail.jpg",
                Instant.now()));
        String jobId = UUID.randomUUID().toString();
        jobRepository.save(new Job(jobId, projectId, JobType.ANALYSIS, 1,
                null, false, true, Instant.now()));

        mockMvc.perform(get("/api/v1/projects").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/projects/{projectId}/assets", projectId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/projects/{projectId}/summary", projectId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/assets/{assetId}", assetId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/assets/{assetId}/original", assetId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/jobs/{jobId}", jobId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/jobs/{jobId}/cancel", jobId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Private photos"));
    }

    private JsonNode register(String email, String password, String displayName) throws Exception {
        String body = objectMapper.writeValueAsString(
                new com.cullpilot.backend.api.auth.AuthRequests.RegisterRequest(
                        email, password, displayName));
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
