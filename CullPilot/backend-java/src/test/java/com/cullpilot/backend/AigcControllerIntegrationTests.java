package com.cullpilot.backend;

import com.cullpilot.backend.domain.asset.AssetRecommendation;
import com.cullpilot.backend.domain.group.AssetGroup;
import com.cullpilot.backend.integration.AnalysisContract;
import com.cullpilot.backend.integration.AigcContract;
import com.cullpilot.backend.integration.PythonApiClient;
import com.cullpilot.backend.repository.aigc.AigcEditRepository;
import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.asset.DecisionHistoryRepository;
import com.cullpilot.backend.repository.export.ExportTaskRepository;
import com.cullpilot.backend.repository.group.AssetGroupRepository;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "aigc-test-user")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/cullpilot-aigc-tests.db",
        "storage.root=./target/cullpilot-aigc-test-storage"
})
class AigcControllerIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AigcEditRepository edits;
    @Autowired private ExportTaskRepository exports;
    @Autowired private DecisionHistoryRepository histories;
    @Autowired private AssetRepository assets;
    @Autowired private AssetGroupRepository groups;
    @Autowired private ProjectRepository projects;
    @MockitoBean private PythonApiClient python;

    @BeforeEach
    void cleanDatabase() {
        exports.deleteAll();
        edits.deleteAll();
        histories.deleteAll();
        assets.deleteAll();
        groups.deleteAll();
        projects.deleteAll();
    }

    @Test
    void editsKeepImageAndExportsOriginalAndGeneratedPng() throws Exception {
        String projectId = createProject();
        String assetId = upload(projectId, "source.jpg", "jpg");
        String groupId = UUID.randomUUID().toString();
        groups.saveAndFlush(new AssetGroup(projectId, 7,
                new AnalysisContract.GroupResult(groupId, "scene", false, List.of(assetId),
                        0.9, 0.9, 0.1, List.of("same scene"), List.of(assetId), null, null)));
        var asset = assets.findById(assetId).orElseThrow();
        asset.setAnalysisResult(groupId, 1, AssetRecommendation.KEEP, 0.9,
                "[]", "same scene", "{}", "{}", "{}");
        assets.saveAndFlush(asset);
        when(python.editImage(any())).thenReturn(new AigcContract.Result(
                Base64.getEncoder().encodeToString(imageBytes("png")), "image/png",
                "natural beautification", "test-provider", "test-model"));

        String editId = createEdit(projectId, assetId, "");
        waitForEdit(editId);

        mockMvc.perform(get("/api/v1/projects/{projectId}/aigc-edits", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupType").value("aigc"))
                .andExpect(jsonPath("$.data.imageCount").value(1))
                .andExpect(jsonPath("$.data.items[0].assetId").value(assetId));
        mockMvc.perform(get("/api/v1/aigc-edits/{editId}/image", editId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mockMvc.perform(get("/api/v1/assets/{assetId}/original", assetId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));

        String exportId = createExport(projectId);
        waitForExport(exportId);
        byte[] archive = mockMvc.perform(get("/api/v1/exports/{exportId}/download", exportId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<String> entries = zipEntries(archive);
        assertThat(entries).anyMatch(name -> name.startsWith("cullpilot-export/group-007/") && name.endsWith(".jpg"));
        assertThat(entries).anyMatch(name -> name.startsWith("cullpilot-export/aigc/") && name.endsWith(".png"));
        assertThat(entries).contains("cullpilot-export/manifest.csv");
        assertThat(zipManifest(archive)).contains("aigc", editId, "natural beautification");
        verify(python, times(1)).editImage(argThat(request -> request.model() == null));

        long version = assets.findById(assetId).orElseThrow().getVersion();
        mockMvc.perform(patch("/api/v1/assets/{assetId}/decision", assetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"reject\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isOk());
        String rejectedExportId = createExport(projectId);
        waitForExport(rejectedExportId);
        byte[] rejectedArchive = mockMvc.perform(get("/api/v1/exports/{exportId}/download", rejectedExportId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zipEntries(rejectedArchive)).containsExactly("cullpilot-export/", "cullpilot-export/manifest.csv");
    }

    @Test
    void rejectsUnselectedAndOtherUsersImages() throws Exception {
        String projectId = createProject();
        String assetId = upload(projectId, "unselected.jpg", "jpg");
        String body = "{\"items\":[{\"assetId\":\"" + assetId + "\"}]}";
        mockMvc.perform(post("/api/v1/projects/{projectId}/aigc-edits", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASSET_NOT_ELIGIBLE_FOR_AIGC"));
        var asset = assets.findById(assetId).orElseThrow();
        asset.setRecommendation(AssetRecommendation.REJECT);
        assets.saveAndFlush(asset);

        mockMvc.perform(post("/api/v1/projects/{projectId}/aigc-edits", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASSET_NOT_ELIGIBLE_FOR_AIGC"));
        mockMvc.perform(post("/api/v1/projects/{projectId}/aigc-edits", projectId)
                        .with(user("another-user"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    private String createProject() throws Exception {
        String json = mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"AIGC test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asText();
    }

    private String upload(String projectId, String filename, String format) throws Exception {
        String json = mockMvc.perform(multipart("/api/v1/projects/{projectId}/assets", projectId)
                        .file(new MockMultipartFile("files", filename, "image/jpeg", imageBytes(format))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("accepted").get(0).path("id").asText();
    }

    private String createEdit(String projectId, String assetId, String prompt) throws Exception {
        String json = mockMvc.perform(post("/api/v1/projects/{projectId}/aigc-edits", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"assetId\":\"" + assetId + "\",\"prompt\":\"" + prompt + "\"}]}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("items").get(0).path("id").asText();
    }

    private String createExport(String projectId) throws Exception {
        String json = mockMvc.perform(post("/api/v1/projects/{projectId}/export", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selection\":\"keep\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asText();
    }

    private void waitForEdit(String editId) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode data = mapper.readTree(mockMvc.perform(get("/api/v1/aigc-edits/{editId}", editId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            if ("succeeded".equals(data.path("status").asText())) return;
            if ("failed".equals(data.path("status").asText())) {
                throw new AssertionError("AIGC task failed: " + data);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("AIGC task did not finish");
    }

    private void waitForExport(String exportId) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode data = mapper.readTree(mockMvc.perform(get("/api/v1/exports/{exportId}", exportId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            if ("succeeded".equals(data.path("status").asText())) return;
            if ("failed".equals(data.path("status").asText())) {
                throw new AssertionError("Export task failed: " + data);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Export task did not finish");
    }

    private List<String> zipEntries(byte[] archive) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }

    private String zipManifest(byte[] archive) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("cullpilot-export/manifest.csv".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Export manifest is missing");
    }

    private byte[] imageBytes(String format) throws Exception {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, new Color(x * 10, y * 10, 100).getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
}
