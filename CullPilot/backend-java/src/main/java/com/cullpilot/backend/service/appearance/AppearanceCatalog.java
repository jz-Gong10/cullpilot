package com.cullpilot.backend.service.appearance;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AppearanceCatalog {

    public static final String VERSION = "appearance-v1";
    public static final String DEFAULT_COLOR_ID = "c01";
    public static final String DEFAULT_STYLE_ID = "flat";
    public static final List<String> DIMENSIONS = List.of(
            "density", "roundness", "structure", "depth", "expressive", "contrast");

    public record Option(String id, String label, Map<String, Integer> scores, List<String> colorFamilies) {
        public Option(String id, String label, Map<String, Integer> scores) {
            this(id, label, scores, List.of());
        }
    }

    public record Question(String id, String title, List<Option> options) {}

    public record Style(String id, String name, Map<String, Integer> vector) {}

    public record ThemeColor(String id, String name, String family, int hue, String hex,
                             String textColor, double contrastRatio) {}

    private static final List<Question> QUESTIONS = List.of(
            new Question("q1", "你希望界面的信息密度是？", List.of(
                    new Option("q1_dense", "一屏看更多内容", Map.of("density", 2)),
                    new Option("q1_balanced", "信息和留白平衡", Map.of("density", 0)),
                    new Option("q1_relaxed", "留白更多、更轻松", Map.of("density", -2)))),
            new Question("q2", "你喜欢什么样的边角？", List.of(
                    new Option("q2_square", "利落直角", Map.of("roundness", -2)),
                    new Option("q2_subtle", "轻微圆角", Map.of("roundness", 0)),
                    new Option("q2_round", "明显圆润", Map.of("roundness", 2)))),
            new Question("q3", "你希望按钮和卡片怎样区分？", List.of(
                    new Option("q3_border", "有清晰边框", Map.of("structure", 2)),
                    new Option("q3_fill", "主要靠底色区分", Map.of("structure", 0)),
                    new Option("q3_open", "尽量少边界", Map.of("structure", -2)))),
            new Question("q4", "你喜欢什么样的层次感？", List.of(
                    new Option("q4_flat", "平面、简洁", Map.of("depth", -2)),
                    new Option("q4_subtle", "有一点阴影和层次", Map.of("depth", 0)),
                    new Option("q4_floating", "明显悬浮和卡片感", Map.of("depth", 2)))),
            new Question("q5", "你希望工具整体给人的感觉是？", List.of(
                    new Option("q5_professional", "专业、理性", Map.of("expressive", -1, "structure", 1)),
                    new Option("q5_soft", "柔和、耐看", Map.of("expressive", -1, "roundness", 1)),
                    new Option("q5_expressive", "有个性、有视觉冲击", Map.of("expressive", 2, "contrast", 1)))),
            new Question("q6", "哪种色彩气质更接近你？", List.of(
                    new Option("q6_clear", "稳定清晰", Map.of(), List.of("blue", "neutral")),
                    new Option("q6_natural", "自然舒缓", Map.of(), List.of("teal", "green")),
                    new Option("q6_energetic", "活泼有能量", Map.of(), List.of("coral", "amber")),
                    new Option("q6_creative", "创意个性", Map.of(), List.of("violet")),
                    new Option("q6_quiet", "克制低干扰", Map.of(), List.of("neutral"))))
    );

    private static final List<Style> STYLES = List.of(
            style("flat", "平面", 0, 0, 0, -2, -1, 0),
            style("outline", "描边", 0, -1, 2, -2, -1, 1),
            style("rounded", "圆润", -1, 2, 0, 0, 0, 0),
            style("compact", "紧凑", 2, -1, 1, -1, -1, 0),
            style("soft", "柔和", -2, 2, -1, 0, -1, -1),
            style("elevated", "悬浮", -1, 1, 0, 2, 0, 0),
            style("glass", "玻璃", -1, 1, -1, 2, 1, -1),
            style("inset", "内嵌", 0, 1, 1, 1, -1, 0),
            style("bold", "醒目", 0, 0, 1, 1, 2, 2),
            style("editorial", "编辑", -1, -1, -1, -1, 2, 1),
            style("technical", "技术", 2, -2, 2, -2, -1, 1),
            style("material", "质感", 0, 1, 0, 1, 0, 0),
            style("retro", "复古", 0, 0, 1, 1, 2, 0),
            style("minimal", "极简", -2, -1, -2, -2, -1, -1),
            style("contrast", "高对比", 1, -1, 2, -1, 2, 2)
    );

    private static final List<ThemeColor> COLORS = List.of(
            color("c01", "晴蓝", "blue", "#2563EB"),
            color("c02", "深海蓝", "blue", "#1D4ED8"),
            color("c03", "钢蓝", "blue", "#0F4C81"),
            color("c04", "靛蓝", "blue", "#3730A3"),
            color("c05", "湖绿", "teal", "#0F766E"),
            color("c06", "海湾", "teal", "#0E7490"),
            color("c07", "深青", "teal", "#116466"),
            color("c08", "水鸭蓝", "teal", "#155E75"),
            color("c09", "叶绿", "green", "#15803D"),
            color("c10", "森林", "green", "#166534"),
            color("c11", "橄榄", "green", "#3F6212"),
            color("c12", "苔绿", "green", "#365314"),
            color("c13", "紫罗兰", "violet", "#7C3AED"),
            color("c14", "深紫", "violet", "#6D28D9"),
            color("c15", "兰紫", "violet", "#7E22CE"),
            color("c16", "梅紫", "violet", "#86198F"),
            color("c17", "珊瑚红", "coral", "#BE123C"),
            color("c18", "暖橘", "coral", "#C2410C"),
            color("c19", "朱红", "coral", "#B42318"),
            color("c20", "莓红", "coral", "#9F1239"),
            color("c21", "琥珀", "amber", "#92400E"),
            color("c22", "赭金", "amber", "#A16207"),
            color("c23", "焦糖", "amber", "#854D0E"),
            color("c24", "石墨", "neutral", "#374151"),
            color("c25", "墨青灰", "neutral", "#334155")
    );

    private static final Map<String, Style> STYLE_BY_ID = STYLES.stream()
            .collect(Collectors.toUnmodifiableMap(Style::id, style -> style));
    private static final Map<String, ThemeColor> COLOR_BY_ID = COLORS.stream()
            .collect(Collectors.toUnmodifiableMap(ThemeColor::id, color -> color));

    private AppearanceCatalog() {}

    public static List<Question> questions() { return QUESTIONS; }
    public static List<Style> styles() { return STYLES; }
    public static List<ThemeColor> colors() { return COLORS; }
    public static Style style(String id) { return STYLE_BY_ID.get(id); }
    public static ThemeColor color(String id) { return COLOR_BY_ID.get(id); }

    private static Style style(String id, String name, int density, int roundness, int structure,
                               int depth, int expressive, int contrast) {
        Map<String, Integer> vector = new LinkedHashMap<>();
        int[] values = {density, roundness, structure, depth, expressive, contrast};
        for (int i = 0; i < DIMENSIONS.size(); i++) {
            vector.put(DIMENSIONS.get(i), values[i]);
        }
        return new Style(id, name, Map.copyOf(vector));
    }

    private static ThemeColor color(String id, String name, String family, String hex) {
        Color rgb = Color.decode(hex);
        int hue = Math.round(Color.RGBtoHSB(rgb.getRed(), rgb.getGreen(), rgb.getBlue(), null)[0] * 360);
        double whiteRatio = contrast(rgb, Color.WHITE);
        double blackRatio = contrast(rgb, Color.BLACK);
        String textColor = whiteRatio >= blackRatio ? "#FFFFFF" : "#000000";
        return new ThemeColor(id, name, family, hue, hex, textColor,
                Math.round(Math.max(whiteRatio, blackRatio) * 100.0) / 100.0);
    }

    private static double contrast(Color a, Color b) {
        double lighter = Math.max(luminance(a), luminance(b));
        double darker = Math.min(luminance(a), luminance(b));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(Color color) {
        int[] channels = {color.getRed(), color.getGreen(), color.getBlue()};
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double value = channels[i] / 255.0;
            linear[i] = value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }
}
