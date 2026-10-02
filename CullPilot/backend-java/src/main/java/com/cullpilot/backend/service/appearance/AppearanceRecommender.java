package com.cullpilot.backend.service.appearance;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.appearance.AppearanceResponses.Combination;
import com.cullpilot.backend.service.appearance.AppearanceCatalog.Option;
import com.cullpilot.backend.service.appearance.AppearanceCatalog.Question;
import com.cullpilot.backend.service.appearance.AppearanceCatalog.Style;
import com.cullpilot.backend.service.appearance.AppearanceCatalog.ThemeColor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AppearanceRecommender {

    public record Result(Combination recommended, List<Combination> alternatives,
                         Map<String, Object> scoreDetail) {}

    public Result recommend(String version, Map<String, String> answers) {
        if (!AppearanceCatalog.VERSION.equals(version)) {
            throw invalid("不支持的问卷版本");
        }
        if (answers == null || answers.size() != AppearanceCatalog.questions().size()) {
            throw invalid("必须为全部六道题各选择一个选项");
        }
        Map<String, Integer> dimensions = new LinkedHashMap<>();
        AppearanceCatalog.DIMENSIONS.forEach(key -> dimensions.put(key, 0));
        List<String> preferredFamilies = List.of();
        for (Question question : AppearanceCatalog.questions()) {
            String answer = answers.get(question.id());
            Option option = question.options().stream().filter(item -> item.id().equals(answer))
                    .findFirst().orElseThrow(() -> invalid("无效的问卷答案: " + question.id()));
            option.scores().forEach((key, value) -> dimensions.merge(key, value, Integer::sum));
            if (!option.colorFamilies().isEmpty()) {
                preferredFamilies = option.colorFamilies();
            }
        }
        final List<String> selectedFamilies = preferredFamilies;

        Map<String, Double> familyScores = new LinkedHashMap<>();
        for (ThemeColor color : AppearanceCatalog.colors()) {
            familyScores.putIfAbsent(color.family(), selectedFamilies.contains(color.family()) ? 5.0 : 0.0);
        }
        List<StyleScore> styles = new ArrayList<>();
        for (Style style : AppearanceCatalog.styles()) {
            double match = 15.0;
            for (String key : AppearanceCatalog.DIMENSIONS) {
                match -= Math.abs(dimensions.get(key) - style.vector().get(key));
            }
            double compatibility = selectedFamilies.stream()
                    .mapToDouble(family -> compatibility(style.id(), family)).max().orElse(0.0);
            double conflict = conflict(style.id(), dimensions);
            styles.add(new StyleScore(style.id(), round(match + compatibility - conflict),
                    round(match), round(compatibility), round(conflict)));
        }
        styles.sort(Comparator.comparingDouble(StyleScore::total).reversed());
        StyleScore winner = styles.get(0);
        familyScores.replaceAll((family, direct) -> round(direct + compatibility(winner.id(), family)));
        String family = familyScores.entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .orElseThrow().getKey();
        List<ThemeColor> safeColors = AppearanceCatalog.colors().stream()
                .filter(color -> color.family().equals(family) && color.contrastRatio() >= 4.5)
                .toList();
        if (safeColors.isEmpty()) {
            throw new IllegalStateException("No accessible color for family " + family);
        }
        ThemeColor color = "contrast".equals(winner.id())
                ? safeColors.stream().max(Comparator.comparingDouble(ThemeColor::contrastRatio)).orElseThrow()
                : safeColors.get(0);
        Combination recommended = new Combination(color.id(), winner.id());
        List<Combination> alternatives = new ArrayList<>();
        for (int i = 1; i < styles.size() && alternatives.size() < 2; i++) {
            StyleScore next = styles.get(i);
            if (winner.total() - next.total() >= 2.0) {
                break;
            }
            alternatives.add(new Combination(color.id(), next.id()));
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dimensions", dimensions);
        detail.put("preferred_families", selectedFamilies);
        detail.put("style_scores", styles);
        detail.put("family_scores", familyScores);
        detail.put("selected_color_contrast_ratio", color.contrastRatio());
        detail.put("selected_text_color", color.textColor());
        return new Result(recommended, List.copyOf(alternatives), detail);
    }

    private double compatibility(String style, String family) {
        return switch (style) {
            case "technical" -> List.of("blue", "teal", "neutral").contains(family) ? 0.75 : 0;
            case "minimal" -> List.of("neutral", "blue", "green").contains(family) ? 0.75 : 0;
            case "glass" -> List.of("teal", "blue", "violet").contains(family) ? 0.75 : 0;
            case "editorial", "retro" -> List.of("coral", "violet", "amber").contains(family) ? 0.75 : 0;
            default -> 0;
        };
    }

    private double conflict(String style, Map<String, Integer> dimensions) {
        if (("glass".equals(style) || "soft".equals(style)) && dimensions.get("contrast") >= 2) {
            return 1.5;
        }
        if ("minimal".equals(style) && dimensions.get("density") >= 2) {
            return 1.5;
        }
        if ("compact".equals(style) && dimensions.get("density") <= -2) {
            return 1.5;
        }
        if ("elevated".equals(style) && dimensions.get("depth") <= -2) {
            return 1.5;
        }
        return 0;
    }

    private double round(double value) { return Math.round(value * 100.0) / 100.0; }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_APPEARANCE_ANSWERS", message);
    }

    public record StyleScore(String id, double total, double match,
                             double compatibility, double conflict) {}
}
