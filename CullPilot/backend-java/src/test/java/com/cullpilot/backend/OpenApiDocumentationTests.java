package com.cullpilot.backend;

import com.cullpilot.backend.service.appearance.AppearanceCatalog;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDocumentationTests {

    @Test
    void appearanceContractParsesAndUsesCatalogIds() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<?, ?> document;
        try (var input = Files.newInputStream(Path.of("../docs/apifox-openapi.yaml"))) {
            document = new Yaml(new SafeConstructor(options)).load(input);
        }

        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        assertThat(paths.keySet().stream().map(Object::toString).toList()).contains(
                "/api/v1/users/me/appearance/onboarding",
                "/api/v1/users/me/appearance/onboarding/skip",
                "/api/v1/users/me/appearance");
        Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
        List<?> colorIds = (List<?>) ((Map<?, ?>) schemas.get("AppearanceColorId")).get("enum");
        List<?> styleIds = (List<?>) ((Map<?, ?>) schemas.get("AppearanceStyleId")).get("enum");
        assertThat(colorIds.stream().map(Object::toString).toList()).containsExactlyElementsOf(AppearanceCatalog.colors().stream()
                .map(AppearanceCatalog.ThemeColor::id).toList());
        assertThat(styleIds.stream().map(Object::toString).toList()).containsExactlyElementsOf(AppearanceCatalog.styles().stream()
                .map(AppearanceCatalog.Style::id).toList());
        assertReferencesResolve(document, document);
    }

    private void assertReferencesResolve(Object value, Map<?, ?> root) {
        if (value instanceof Map<?, ?> map) {
            Object ref = map.get("$ref");
            if (ref instanceof String reference) {
                assertThat(reference).startsWith("#/");
                Object target = root;
                for (String part : reference.substring(2).split("/")) {
                    assertThat(target).isInstanceOf(Map.class);
                    target = ((Map<?, ?>) target).get(part.replace("~1", "/").replace("~0", "~"));
                    assertThat(target).as(reference).isNotNull();
                }
            }
            map.values().forEach(item -> assertReferencesResolve(item, root));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> assertReferencesResolve(item, root));
        }
    }
}
