package com.cullpilot.backend;

import com.cullpilot.backend.repository.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test-user")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/cullpilot-project-tests.db",
        "storage.root=./target/cullpilot-project-test-storage"
})
class ProjectControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void cleanProjects() {
        projectRepository.deleteAll();
    }

    @Test
    void createsProjectWithDefaultSettings() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "测试项目"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("测试项目"))
                .andExpect(jsonPath("$.data.status").value("created"))
                .andExpect(jsonPath("$.data.settings.keepPerGroup").value(2))
                .andExpect(jsonPath("$.data.settings.strictness").value("standard"))
                .andExpect(jsonPath("$.data.settings.contentMode").value("auto"))
                .andExpect(jsonPath("$.data.settings.privacy.stripGpsOnExport").value(true));
    }

    @Test
    void listsProjectsWithPagination() throws Exception {
        createProject("项目一");
        createProject("项目二");

        mockMvc.perform(get("/api/v1/projects?page=1&pageSize=1&sort=name:asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(1));
    }

    @Test
    void updatesProjectSettingsWithValidation() throws Exception {
        String projectId = createProject("设置测试");

        mockMvc.perform(patch("/api/v1/projects/{projectId}/settings", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keepPerGroup": 3,
                                  "strictness": "strict",
                                  "contentMode": "mixed",
                                  "privacy": {
                                    "stripGpsOnExport": false
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings.keepPerGroup").value(3))
                .andExpect(jsonPath("$.data.settings.strictness").value("strict"))
                .andExpect(jsonPath("$.data.settings.contentMode").value("mixed"))
                .andExpect(jsonPath("$.data.settings.privacy.stripGpsOnExport").value(false));

        mockMvc.perform(patch("/api/v1/projects/{projectId}/settings", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keepPerGroup\":11}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returnsNotFoundForUnknownProject() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void rejectsUnknownContentMode() throws Exception {
        String projectId = createProject("内容模式校验");

        mockMvc.perform(patch("/api/v1/projects/{projectId}/settings", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentMode\":\"cinematic\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_PROJECT_SETTINGS"));
    }

    @Test
    void deletesProjectAndReturnsCleanupJob() throws Exception {
        String projectId = createProject("删除测试");

        mockMvc.perform(delete("/api/v1/projects/{projectId}", projectId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.projectId").value(projectId))
                .andExpect(jsonPath("$.data.job.type").value("projectCleanup"))
                .andExpect(jsonPath("$.data.job.status").value("succeeded"));

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId))
                .andExpect(status().isNotFound());
    }

    private String createProject(String name) throws Exception {
        String response = mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return response.replaceFirst(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }
}
