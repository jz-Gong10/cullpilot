package com.cullpilot.backend;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/cullpilot-appearance-tests.db",
        "storage.root=./target/cullpilot-appearance-test-storage"
})
class AppearanceControllerIntegrationTests {

    private static final String BASE = "/api/v1/users/me/appearance";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM appearance_questionnaire_records");
        jdbc.update("DELETE FROM user_appearances");
        jdbc.update("DELETE FROM user_sessions");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void servesStableCatalogAndRequiresAuthentication() throws Exception {
        mockMvc.perform(get(BASE + "/onboarding")).andExpect(status().isUnauthorized());
        String token = register();
        JsonNode catalog = data(mockMvc.perform(get(BASE + "/onboarding")
                .header("Authorization", bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questionnaire_version").value("appearance-v1"))
                .andExpect(jsonPath("$.data.onboarding_required").value(true))
                .andReturn().getResponse().getContentAsString());
        assertThat(catalog.path("questions").size()).isEqualTo(6);
        assertThat(catalog.path("colors").size()).isEqualTo(25);
        assertThat(catalog.path("styles").size()).isEqualTo(15);
        assertThat(catalog.path("colors").get(0).path("id").asText()).isEqualTo("c01");
        assertThat(catalog.path("styles").get(0).path("id").asText()).isEqualTo("flat");
        assertThat(catalog.path("colors")).allSatisfy(color ->
                assertThat(color.path("contrastRatio").asDouble()).isGreaterThanOrEqualTo(4.5));

        mockMvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.color_id").value("c01"))
                .andExpect(jsonPath("$.data.style_id").value("flat"))
                .andExpect(jsonPath("$.data.source").value("default"));
    }

    @Test
    void validatesAnswersPersistsRecommendationAndProtectsManualChoice() throws Exception {
        String token = register();
        Map<String, String> answers = Map.of(
                "q1", "q1_dense", "q2", "q2_square", "q3", "q3_border",
                "q4", "q4_flat", "q5", "q5_professional", "q6", "q6_clear");

        mockMvc.perform(post(BASE + "/onboarding")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "questionnaire_version", "appearance-v1", "answers", Map.of("q1", "q1_dense"),
                                "apply", true))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_APPEARANCE_ANSWERS"));
        mockMvc.perform(post(BASE + "/onboarding")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "questionnaire_version", "appearance-v2", "answers", answers,
                                "apply", true))))
                .andExpect(status().isUnprocessableEntity());

        JsonNode preview = submit(token, answers, false, false);
        assertThat(preview.path("recommended").path("style_id").asText()).isEqualTo("technical");
        assertThat(preview.path("score_detail").path("dimensions").path("density").asInt()).isEqualTo(2);
        assertThat(preview.path("alternatives").size()).isLessThanOrEqualTo(2);
        mockMvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.source").value("default"))
                .andExpect(jsonPath("$.data.onboarding_required").value(false));
        assertThat(jdbc.queryForObject("SELECT applied FROM appearance_questionnaire_records", Integer.class))
                .isZero();

        JsonNode applied = submit(token, answers, true, false);
        assertThat(applied.path("applied").asBoolean()).isTrue();
        mockMvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.style_id").value("technical"))
                .andExpect(jsonPath("$.data.source").value("onboarding"))
                .andExpect(jsonPath("$.data.questionnaire_version").value("appearance-v1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appearance_questionnaire_records", Integer.class))
                .isEqualTo(2);
        String storedAnswers = jdbc.queryForObject(
                "SELECT answers_json FROM appearance_questionnaire_records WHERE applied = 1", String.class);
        assertThat(mapper.readTree(storedAnswers).path("q6").asText()).isEqualTo("q6_clear");

        mockMvc.perform(put(BASE).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color_id\":\"c99\",\"style_id\":\"glass\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put(BASE).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color_id\":\"c13\",\"style_id\":\"glass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("manual"));
        mockMvc.perform(post(BASE + "/onboarding")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "questionnaire_version", "appearance-v1", "answers", answers,
                                "apply", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MANUAL_APPEARANCE_PROTECTED"));
        mockMvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.color_id").value("c13"))
                .andExpect(jsonPath("$.data.style_id").value("glass"));

        submit(token, answers, true, true);
        mockMvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.source").value("onboarding"));
    }

    @Test
    void skipKeepsDefaultsAndStopsFirstLoginPrompt() throws Exception {
        String token = register();
        mockMvc.perform(post(BASE + "/onboarding/skip").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.color_id").value("c01"))
                .andExpect(jsonPath("$.data.style_id").value("flat"))
                .andExpect(jsonPath("$.data.source").value("default"))
                .andExpect(jsonPath("$.data.onboarding_status").value("skipped"));
        String email = jdbc.queryForObject("SELECT email FROM users", String.class);
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "Password123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appearanceOnboardingRequired").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appearance_questionnaire_records", Integer.class))
                .isZero();
    }

    @Test
    void appearanceIsIsolatedPerUser() throws Exception {
        String first = register();
        String second = register();
        mockMvc.perform(put(BASE).header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"color_id\":\"c13\",\"style_id\":\"glass\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE).header("Authorization", bearer(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.color_id").value("c01"))
                .andExpect(jsonPath("$.data.style_id").value("flat"))
                .andExpect(jsonPath("$.data.onboarding_required").value(true));
    }

    private JsonNode submit(String token, Map<String, String> answers,
                            boolean apply, boolean restart) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "questionnaire_version", "appearance-v1", "answers", answers,
                "apply", apply, "restart", restart));
        String response = mockMvc.perform(post(BASE + "/onboarding")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return data(response);
    }

    private String register() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "Password123!"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.appearanceOnboardingRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        return data(response).path("accessToken").asText();
    }

    private JsonNode data(String body) throws Exception { return mapper.readTree(body).path("data"); }
    private String bearer(String token) { return "Bearer " + token; }
}
