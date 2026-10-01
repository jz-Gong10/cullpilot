package com.cullpilot.backend;

import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.asset.DecisionHistoryRepository;
import com.cullpilot.backend.domain.aigc.AigcEdit;
import com.cullpilot.backend.domain.export.ExportTask;
import com.cullpilot.backend.domain.job.Job;
import com.cullpilot.backend.domain.job.JobType;
import com.cullpilot.backend.repository.aigc.AigcEditRepository;
import com.cullpilot.backend.repository.export.ExportTaskRepository;
import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.domain.group.AssetGroup;
import com.cullpilot.backend.integration.AnalysisContract;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.repository.group.AssetGroupRepository;
import com.cullpilot.backend.repository.job.JobRepository;
import com.cullpilot.backend.service.aigc.AigcStorageService;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
import static org.assertj.core.api.Assertions.assertThat;

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

    @Autowired
    private AigcEditRepository aigcEditRepository;

    @Autowired
    private AigcStorageService aigcStorageService;

    @Autowired
    private JobRepository jobRepository;

    @BeforeEach
    void cleanDatabase() {
        exportTaskRepository.deleteAll();
        aigcEditRepository.deleteAll();
        decisionHistoryRepository.deleteAll();
        jobRepository.deleteAll();
        groupRepository.deleteAll();
        assetRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void deletesOneImageAndUpdatesProjectAndGroup() throws Exception {
        String projectId = createProject();
        String firstId = upload(projectId, "first.png", 20);
        String secondId = upload(projectId, "second.png", 25);
        String groupId = UUID.randomUUID().toString();
        groupRepository.saveAndFlush(new AssetGroup(projectId, 1,
                new AnalysisContract.GroupResult(groupId, "scene", false, List.of(firstId, secondId),
                        0.9, 0.9, 0.1, List.of("same scene"), List.of(firstId, secondId), null, null)));
        var first = assetRepository.findById(firstId).orElseThrow();
        first.setAnalysisResult(groupId, 1, AssetRecommendation.KEEP, 0.9,
                "[]", "same scene", "{}", "{}", "{}");
        assetRepository.saveAndFlush(first);
        var second = assetRepository.findById(secondId).orElseThrow();
        second.setAnalysisResult(groupId, 2, AssetRecommendation.REVIEW, 0.8,
                "[]", "same scene", "{}", "{}", "{}");
        assetRepository.saveAndFlush(second);
        Path original = storagePath(first.getOriginalPath());
        Path thumbnail = storagePath(first.getThumbnailPath());
        assertThat(original).exists();
        assertThat(thumbnail).exists();

        mockMvc.perform(patch("/api/v1/assets/{assetId}/decision", firstId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"reject\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/assets/{assetId}", firstId))
                .andExpect(status().isNoContent());

        assertThat(assetRepository.existsById(firstId)).isFalse();
        assertThat(decisionHistoryRepository.count()).isZero();
        assertThat(original).doesNotExist();
        assertThat(thumbnail).doesNotExist();
        assertThat(groupRepository.findById(groupId).orElseThrow().getAssetCount()).isEqualTo(1);
        assertThat(groupRepository.findById(groupId).orElseThrow().getRecommendedAssetIds())
                .containsExactly(secondId);
        mockMvc.perform(get("/api/v1/projects/{projectId}/assets", projectId))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/assets/{assetId}", secondId))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/assets/{assetId}", firstId))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/assets/{assetId}", secondId))
                .andExpect(status().isNoContent());
        assertThat(groupRepository.existsById(groupId)).isFalse();
        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId))
                .andExpect(jsonPath("$.data.assetCount").value(0))
                .andExpect(jsonPath("$.data.groupCount").value(0))
                .andExpect(jsonPath("$.data.status").value("created"));
        assertThat(upload(projectId, "first.png", 20)).isNotEqualTo(firstId);
    }

    @Test
    void cannotDeleteAnotherUsersImage() throws Exception {
        String projectId = createProject();
        String assetId = upload(projectId, "private.png", 20);
        mockMvc.perform(delete("/api/v1/assets/{assetId}", assetId).with(user("another-user")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
        assertThat(assetRepository.existsById(assetId)).isTrue();
    }

    @Test
    void deletesFinishedAigcResultWithSourceImage() throws Exception {
        String projectId = createProject();
        String assetId = upload(projectId, "edited.png", 20);
        String editId = UUID.randomUUID().toString();
        String encoded = Base64.getEncoder().encodeToString(image("generated.png", "png", 20, 20).getBytes());
        var generated = aigcStorageService.store(projectId, editId, encoded, "image/png");
        AigcEdit edit = new AigcEdit(editId, projectId, assetId, "", "test-model",
                "1024*1024", true, false, Instant.now());
        edit.markSucceeded("enhance", "test", "test-model", generated.path(),
                "image/png", generated.sizeBytes(), Instant.now());
        aigcEditRepository.saveAndFlush(edit);

        mockMvc.perform(delete("/api/v1/assets/{assetId}", assetId))
                .andExpect(status().isNoContent());
        assertThat(aigcEditRepository.existsById(editId)).isFalse();
        assertThat(storagePath(generated.path())).doesNotExist();
        mockMvc.perform(get("/api/v1/aigc-edits/{editId}", editId))
                .andExpect(status().isNotFound());
    }

    @Test
    void newExportExcludesDeletedImageButOldArchiveRemainsAvailable() throws Exception {
        String projectId = createProject();
        String assetId = upload(projectId, "exported.png", 20);
        var asset = assetRepository.findById(assetId).orElseThrow();
        asset.setRecommendation(AssetRecommendation.KEEP);
        assetRepository.saveAndFlush(asset);

        String oldExportId = createExport(projectId);
        waitForExport(oldExportId);
        mockMvc.perform(delete("/api/v1/assets/{assetId}", assetId))
                .andExpect(status().isNoContent());

        byte[] oldArchive = mockMvc.perform(get("/api/v1/exports/{exportId}/download", oldExportId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zipEntries(oldArchive)).anyMatch(name -> name.endsWith("-exported.png"));

        String newExportId = createExport(projectId);
        waitForExport(newExportId);
        byte[] newArchive = mockMvc.perform(get("/api/v1/exports/{exportId}/download", newExportId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zipEntries(newArchive)).noneMatch(name -> name.endsWith("-exported.png"));
    }

    @Test
    void rejectsDeletionWhileTasksUseTheImage() throws Exception {
        String projectId = createProject();
        String assetId = upload(projectId, "busy.png", 20);
        String editId = UUID.randomUUID().toString();
        aigcEditRepository.saveAndFlush(new AigcEdit(editId, projectId, assetId, "",
                "test-model", "1024*1024", true, false, Instant.now()));
        mockMvc.perform(delete("/api/v1/assets/{assetId}", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AIGC_IN_PROGRESS"));
        aigcEditRepository.deleteById(editId);

        String exportId = UUID.randomUUID().toString();
        exportTaskRepository.saveAndFlush(new ExportTask(exportId, projectId,
                ExportTask.Selection.KEEP, true, false, true));
        mockMvc.perform(delete("/api/v1/assets/{assetId}", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXPORT_IN_PROGRESS"));
        exportTaskRepository.deleteById(exportId);

        jobRepository.saveAndFlush(new Job(UUID.randomUUID().toString(), projectId,
                JobType.ANALYSIS, 1, null, false, true, Instant.now()));
        mockMvc.perform(delete("/api/v1/assets/{assetId}", assetId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_IN_PROGRESS"));
        assertThat(assetRepository.existsById(assetId)).isTrue();
    }

    private String upload(String projectId, String name, int size) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(image(name, "png", size, size)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return response.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private Path storagePath(String relativePath) {
        return Path.of("target/cullpilot-asset-test-storage").resolve(relativePath);
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
