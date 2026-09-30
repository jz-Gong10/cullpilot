package com.cullpilot.backend;

import com.cullpilot.backend.domain.job.Job;
import com.cullpilot.backend.domain.job.JobStatus;
import com.cullpilot.backend.domain.job.JobType;
import com.cullpilot.backend.domain.asset.AssetDecision;
import com.cullpilot.backend.integration.AnalysisContract;
import com.cullpilot.backend.integration.PythonApiClient;
import com.cullpilot.backend.repository.group.AssetGroupRepository;
import com.cullpilot.backend.domain.project.Project;
import com.cullpilot.backend.domain.project.ProjectStatus;
import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.job.JobErrorRepository;
import com.cullpilot.backend.repository.job.JobRepository;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test-user")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/cullpilot-job-tests.db",
        "storage.root=./target/cullpilot-job-test-storage"
})
class JobControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobErrorRepository jobErrorRepository;

    @Autowired
    private AssetGroupRepository groupRepository;

    @MockitoBean
    private PythonApiClient pythonApiClient;

    @BeforeEach
    void cleanDatabase() {
        reset(pythonApiClient);
        jobErrorRepository.deleteAll();
        jobRepository.deleteAll();
        assetRepository.deleteAll();
        groupRepository.deleteAll();
        projectRepository.deleteAll();
        when(pythonApiClient.analyze(any())).thenAnswer(invocation -> sampleResult(invocation.getArgument(0)));
    }

    @Test
    void rejectsAnalysisWhenProjectHasNoAssets() throws Exception {
        String projectId = createProject();

        mockMvc.perform(post("/api/v1/projects/{projectId}/analyze", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"force\":false,\"rebuildGroups\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PROJECT_HAS_NO_ASSETS"));
    }

    @Test
    void startsQueriesAndSummarizesAnalysis() throws Exception {
        String projectId = createProject();
        uploadImage(projectId);

        String firstResponse = mockMvc.perform(post("/api/v1/projects/{projectId}/analyze", projectId)
                        .header("Idempotency-Key", "analysis-test-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"force\":false,\"rebuildGroups\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.type").value("analysis"))
                .andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readTree(firstResponse).path("data").path("id").asText();

        mockMvc.perform(post("/api/v1/projects/{projectId}/analyze", projectId)
                        .header("Idempotency-Key", "analysis-test-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(jobId));

        awaitTerminal(jobId);

        mockMvc.perform(get("/api/v1/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.progress").value(100))
                .andExpect(jsonPath("$.data.processedCount").value(1))
                .andExpect(jsonPath("$.data.errorCount").value(0));

        mockMvc.perform(get("/api/v1/projects/{projectId}/summary", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ready"))
                .andExpect(jsonPath("$.data.assetCount").value(1))
                .andExpect(jsonPath("$.data.analyzedCount").value(1))
                .andExpect(jsonPath("$.data.decisionCounts.review").value(1))
                .andExpect(jsonPath("$.data.activeJobId").isEmpty());

        mockMvc.perform(get("/api/v1/projects/{projectId}/groups", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].assetCount").value(1));
    }

    @Test
    void cancelsAndRetriesEligibleJob() throws Exception {
        String projectId = createProject();
        uploadImage(projectId);

        Project project = projectRepository.findById(projectId).orElseThrow();
        project.setStatus(ProjectStatus.ANALYZING);
        projectRepository.save(project);

        String cancelledJobId = UUID.randomUUID().toString();
        jobRepository.save(new Job(
                cancelledJobId,
                projectId,
                JobType.ANALYSIS,
                1,
                null,
                false,
                true,
                Instant.now()));

        mockMvc.perform(post("/api/v1/jobs/{jobId}/cancel", cancelledJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("cancelled"));

        String retryResponse = mockMvc.perform(post("/api/v1/jobs/{jobId}/retry", cancelledJobId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("queued"))
                .andReturn().getResponse().getContentAsString();
        String retryJobId = objectMapper.readTree(retryResponse).path("data").path("id").asText();
        awaitTerminal(retryJobId);

        mockMvc.perform(get("/api/v1/jobs/{jobId}", retryJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"));
    }

    @Test
    void analysisPreservesUserDecisionAndRestrictsGroupQueries() throws Exception {
        String projectId = createProject();
        uploadImage(projectId);
        var asset = assetRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId).get(0);
        asset.setDecision(AssetDecision.KEEP);
        assetRepository.saveAndFlush(asset);

        String response = mockMvc.perform(post("/api/v1/projects/{projectId}/analyze", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        awaitTerminal(objectMapper.readTree(response).path("data").path("id").asText());
        var analyzed = assetRepository.findById(asset.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(analyzed.getDecision()).isEqualTo(AssetDecision.KEEP);
        org.assertj.core.api.Assertions.assertThat(analyzed.getRecommendation().value()).isEqualTo("reject");

        String groupId = analyzed.getGroupId();
        mockMvc.perform(get("/api/v1/groups/{groupId}/assets", groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].recommendation").value("reject"))
                .andExpect(jsonPath("$.data.items[0].decision").value("keep"))
                .andExpect(jsonPath("$.data.items[0].rank").value(1));
        mockMvc.perform(get("/api/v1/groups/{groupId}/assets", groupId).with(user("another-user")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/{projectId}/groups", projectId).with(user("another-user")))
                .andExpect(status().isNotFound());
    }

    @Test
    void pythonFailureMarksJobAndAssetFailed() throws Exception {
        String projectId = createProject();
        uploadImage(projectId);
        doThrow(new IllegalStateException("Python API unavailable")).when(pythonApiClient).analyze(any());
        String response = mockMvc.perform(post("/api/v1/projects/{projectId}/analyze", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = objectMapper.readTree(response).path("data").path("id").asText();
        awaitTerminal(jobId);
        mockMvc.perform(get("/api/v1/jobs/{jobId}", jobId))
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.errorCount").value(1));
        org.assertj.core.api.Assertions.assertThat(assetRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId).get(0)
                .getAnalysisStatus().value()).isEqualTo("failed");
    }

    private AnalysisContract.Result sampleResult(AnalysisContract.Request request) {
        String groupId = UUID.randomUUID().toString();
        List<String> ids = request.assets().stream().map(AnalysisContract.InputAsset::assetId).toList();
        var group = new AnalysisContract.GroupResult(groupId, "scene", false, ids, 0.9, 0.9, 0.1,
                List.of("Similar images"), List.of(), null, null);
        var results = ids.stream().map(id -> new AnalysisContract.AssetResult(id, groupId, 1,
                "reject", 0.4, List.of("Low score"), "Similar scene",
                Map.of("overall_score", 0.4), Map.of("sharpness", 0.4), Map.of())).toList();
        return new AnalysisContract.Result(request.projectId(), List.of(group), results, List.of());
    }

    private String createProject() throws Exception {
        String response = mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Analysis API test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asText();
    }

    private void uploadImage(String projectId) throws Exception {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, new Color(x * 5, y * 7, 120).getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        MockMultipartFile file = new MockMultipartFile(
                "files", "analysis.png", "image/png", output.toByteArray());

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId).file(file))
                .andExpect(status().isCreated());
    }

    private void awaitTerminal(String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            JobStatus status = jobRepository.findById(jobId).orElseThrow().getStatus();
            if (status != JobStatus.QUEUED && status != JobStatus.RUNNING) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Analysis job did not finish in time: " + jobId);
    }
}
