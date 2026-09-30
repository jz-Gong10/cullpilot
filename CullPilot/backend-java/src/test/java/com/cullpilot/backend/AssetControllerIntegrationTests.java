package com.cullpilot.backend;

import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.asset.DecisionHistoryRepository;
import com.cullpilot.backend.repository.export.ExportTaskRepository;
import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.domain.group.AssetGroup;
import com.cullpilot.backend.integration.AnalysisContract;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.repository.group.AssetGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test-user")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/cullpilot-asset-tests.db",
        "storage.root=./target/cullpilot-asset-test-storage"
})
class AssetControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private DecisionHistoryRepository decisionHistoryRepository;

    @Autowired
    private ExportTaskRepository exportTaskRepository;

    @Autowired
    private AssetGroupRepository groupRepository;

    @BeforeEach
    void cleanDatabase() {
        exportTaskRepository.deleteAll();
        decisionHistoryRepository.deleteAll();
        groupRepository.deleteAll();
        assetRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void updatesDecisionAndRecordsHistory() throws Exception {
        String projectId = createProject();
        String uploadResponse = mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(image("decision.png", "png", 20, 20)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String assetId = uploadResponse.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(patch("/api/v1/assets/{assetId}/decision", assetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"keep\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("keep"))
                .andExpect(jsonPath("$.data.version").value(1));

        org.assertj.core.api.Assertions.assertThat(decisionHistoryRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsStaleDecisionVersion() throws Exception {
        String projectId = createProject();
        String uploadResponse = mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(image("stale.png", "png", 20, 20)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String assetId = uploadResponse.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(patch("/api/v1/assets/{assetId}/decision", assetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"keep\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/assets/{assetId}/decision", assetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"reject\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void cannotChangeAnotherUsersDecision() throws Exception {
        String projectId = createProject();
        String uploadResponse = mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(image("private.png", "png", 20, 20)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String assetId = uploadResponse.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(patch("/api/v1/assets/{assetId}/decision", assetId)
                        .with(user("another-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"reject\",\"expectedVersion\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void exportUsesAiRecommendationUntilUserMakesADecision() throws Exception {
        String projectId = createProject();
        String uploadResponse = mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(image("export.png", "png", 20, 20)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String assetId = uploadResponse.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        var recommended = assetRepository.findById(assetId).orElseThrow();
        recommended.setRecommendation(AssetRecommendation.KEEP);
        assetRepository.saveAndFlush(recommended);

        String exportId = createExport(projectId);
        waitForExport(exportId);
        byte[] firstArchive = mockMvc.perform(get("/api/v1/exports/{exportId}/download", exportId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(zipEntries(firstArchive))
                .contains("cullpilot-export/", "cullpilot-export/ungrouped/")
                .anyMatch(entry -> entry.startsWith("cullpilot-export/ungrouped/"))
                .anyMatch(entry -> entry.equals("cullpilot-export/manifest.csv"));

        mockMvc.perform(patch("/api/v1/assets/{assetId}/decision", assetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"reject\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        String secondExportId = createExport(projectId);
        waitForExport(secondExportId);
        byte[] secondArchive = mockMvc.perform(get("/api/v1/exports/{exportId}/download", secondExportId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(zipEntries(secondArchive))
                .contains("cullpilot-export/", "cullpilot-export/manifest.csv")
                .noneMatch(entry -> entry.startsWith("cullpilot-export/ungrouped/")
                        && !entry.equals("cullpilot-export/ungrouped/"));
    }

    @Test
    void exportPlacesImagesInsideTheirNumberedGroupDirectory() throws Exception {
        String projectId = createProject();
        String uploadResponse = mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(image("grouped.png", "png", 20, 20)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String assetId = uploadResponse.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
        String groupId = UUID.randomUUID().toString();

        groupRepository.saveAndFlush(new AssetGroup(projectId, 7,
                new AnalysisContract.GroupResult(groupId, "scene", false, List.of(assetId),
                        0.9, 0.9, 0.1, List.of("same scene"), List.of(assetId), null, null)));
        var asset = assetRepository.findById(assetId).orElseThrow();
        asset.setAnalysisResult(groupId, 1, AssetRecommendation.KEEP, 0.9,
                "[]", "same scene", "{}", "{}", "{}");
        assetRepository.saveAndFlush(asset);

        String exportId = createExport(projectId);
        waitForExport(exportId);
        byte[] archive = mockMvc.perform(get("/api/v1/exports/{exportId}/download", exportId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        org.assertj.core.api.Assertions.assertThat(zipEntries(archive))
                .contains("cullpilot-export/", "cullpilot-export/group-007/")
                .anyMatch(entry -> entry.startsWith("cullpilot-export/group-007/")
                        && entry.endsWith("-grouped.png"));
    }

    private String createExport(String projectId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/projects/{projectId}/export", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selection\":\"keep\",\"includeManifest\":true}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return response.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void waitForExport(String exportId) throws Exception {
        for (int i = 0; i < 50; i++) {
            String response = mockMvc.perform(get("/api/v1/exports/{exportId}", exportId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (response.contains("\"status\":\"succeeded\"")) {
                return;
            }
            if (response.contains("\"status\":\"failed\"")) {
                throw new AssertionError("Export task failed: " + response);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Export task did not finish");
    }

    private List<String> zipEntries(byte[] archive) throws Exception {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }

    @Test
    void uploadsListsAndReadsImage() throws Exception {
        String projectId = createProject();
        MockMultipartFile image = image("photo.png", "png", 800, 600);

        String uploadResponse = mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(image)
                        .header("Idempotency-Key", "upload-test-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accepted.length()").value(1))
                .andExpect(jsonPath("$.data.accepted[0].mimeType").value("image/png"))
                .andExpect(jsonPath("$.data.accepted[0].width").value(800))
                .andExpect(jsonPath("$.data.accepted[0].height").value(600))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String assetId = uploadResponse.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/api/v1/projects/{projectId}/assets", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/v1/assets/{assetId}", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value(projectId))
                .andExpect(jsonPath("$.data.decision").value("review"));

        mockMvc.perform(get("/api/v1/assets/{assetId}/thumbnail", assetId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));

        mockMvc.perform(get("/api/v1/assets/{assetId}/original", assetId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void identifiesDuplicateBySha256() throws Exception {
        String projectId = createProject();
        MockMultipartFile image = image("photo.png", "png", 20, 20);

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId).file(image))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accepted.length()").value(1));

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(image("copy.png", "png", 20, 20)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accepted.length()").value(0))
                .andExpect(jsonPath("$.data.duplicates.length()").value(1));
    }

    @Test
    void acceptsRepeatedFileFields() throws Exception {
        String projectId = createProject();
        mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(new MockMultipartFile("file", "first.png", "image/png", image("first.png", "png", 20, 20).getBytes()))
                        .file(new MockMultipartFile("file", "second.png", "image/png", image("second.png", "png", 25, 25).getBytes())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accepted.length()").value(2));
    }

    @Test
    void rejectsUploadWithoutAFileField() throws Exception {
        String projectId = createProject();
        mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NO_FILES"));
    }

    @Test
    void rejectsUnsupportedFileType() throws Exception {
        String projectId = createProject();
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "bad.gif",
                "image/gif",
                "not-a-real-gif".getBytes());

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId).file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_BATCH_FAILED"));
    }

    private String createProject() throws Exception {
        String response = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/projects")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"图片接口测试\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private MockMultipartFile image(String filename, String format, int width, int height)
            throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, new Color((x * 255) / width, (y * 255) / height, 120).getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return new MockMultipartFile("files", filename, "image/" + format, output.toByteArray());
    }
}
