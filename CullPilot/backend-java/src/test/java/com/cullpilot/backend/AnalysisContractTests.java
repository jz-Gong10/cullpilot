package com.cullpilot.backend;

import com.cullpilot.backend.integration.AnalysisContract;
import com.cullpilot.backend.integration.InstructionContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/cullpilot-contract-tests.db",
        "storage.root=./target/cullpilot-contract-test-storage"
})
class AnalysisContractTests {
    @Autowired
    ObjectMapper mapper;

    @Test
    void sendsSnakeCaseAndReadsPythonResult() throws Exception {
        var request = new AnalysisContract.Request("project", List.of(new AnalysisContract.InputAsset("asset", "file")),
                new AnalysisContract.Strategy(2, "standard", "mixed", Map.of(), Map.of()), true, List.of());
        String json = mapper.writeValueAsString(request);
        assertThat(json).contains("\"project_id\"").contains("\"asset_id\"")
                .contains("\"file_path\"").contains("\"keep_per_group\"")
                .contains("\"rebuild_groups\"");

        String response = """
                {"project_id":"project","algorithm":{"name":"demo-photo-selection","version":"1.0"},
                "groups":[{"id":"group","group_type":"scene","has_face":false,"asset_ids":["asset"],
                "confidence":0.9,"average_similarity":0.9,"max_distance":0.1,"reasons":["visual"],
                "recommended_asset_ids":["asset"],"time_from":null,"time_to":null}],"assets":[{"asset_id":"asset",
                "group_id":"group","rank":1,"recommendation":"keep","recommend_score":0.8,
                "recommend_reasons":[],"membership_reason":"similar","quality":{},"features":{},"exif":{}}],
                "warnings":[]}
                """;
        var parsed = mapper.readValue(response, AnalysisContract.Result.class);
        assertThat(parsed.algorithm()).containsEntry("name", "demo-photo-selection");
        assertThat(parsed.groups()).hasSize(1);
        assertThat(parsed.groups().get(0).recommendedAssetIds()).containsExactly("asset");
        assertThat(parsed.assets().get(0).recommendScore()).isEqualTo(0.8);
        assertThat(parsed.assets().get(0).membershipReason()).isEqualTo("similar");
    }

    @Test
    void instructionRequestAndResponseMatchPythonContract() throws Exception {
        String request = mapper.writeValueAsString(new InstructionContract.Request("clear photos", List.of("sharpness")));
        assertThat(request).contains("\"allowed_features\"").doesNotContain("allowedFeatures");

        var response = mapper.readValue("""
                {"strategy":{"keep_per_group":3,"strictness":"standard","content_mode":"auto",
                "weights":{},"constraints":{},"unsupported_terms":[],"explanation":"ok"},
                "confidence":0.8,"fallback_used":false,"provider":"builtin","model":"rule-based-v1"}
                """, InstructionContract.Response.class);
        assertThat(response.strategy().keepPerGroup()).isEqualTo(3);
        assertThat(response.strategy().contentMode()).isEqualTo("auto");
    }
}
