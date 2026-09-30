package com.cullpilot.backend.domain.project;

import java.util.LinkedHashMap;
import java.util.Map;

public class ProjectSettings {

    public static final Map<String, Double> DEFAULT_WEIGHTS = defaultWeights();
    public static final Map<String, Boolean> DEFAULT_CONSTRAINTS = defaultConstraints();

    private int keepPerGroup;
    private Strictness strictness;
    private ContentMode contentMode;
    private Map<String, Double> weights;
    private Map<String, Boolean> constraints;
    private ProjectPrivacySettings privacy;

    public ProjectSettings() {
        this(2, Strictness.STANDARD, ContentMode.AUTO, DEFAULT_WEIGHTS, DEFAULT_CONSTRAINTS,
                new ProjectPrivacySettings());
    }

    public ProjectSettings(
            int keepPerGroup,
            Strictness strictness,
            ContentMode contentMode,
            Map<String, Double> weights,
            Map<String, Boolean> constraints,
            ProjectPrivacySettings privacy) {
        this.keepPerGroup = keepPerGroup;
        this.strictness = strictness;
        this.contentMode = contentMode;
        this.weights = new LinkedHashMap<>(weights);
        this.constraints = new LinkedHashMap<>(constraints);
        this.privacy = privacy == null ? new ProjectPrivacySettings() : privacy.copy();
    }

    public static ProjectSettings defaults() {
        return new ProjectSettings(
                2,
                Strictness.STANDARD,
                ContentMode.AUTO,
                defaultWeights(),
                defaultConstraints(),
                new ProjectPrivacySettings());
    }

    public ProjectSettings copy() {
        return new ProjectSettings(keepPerGroup, strictness, contentMode, weights, constraints, privacy);
    }

    public int getKeepPerGroup() {
        return keepPerGroup;
    }

    public void setKeepPerGroup(int keepPerGroup) {
        this.keepPerGroup = keepPerGroup;
    }

    public Strictness getStrictness() {
        return strictness;
    }

    public void setStrictness(Strictness strictness) {
        this.strictness = strictness;
    }

    public ContentMode getContentMode() {
        return contentMode;
    }

    public void setContentMode(ContentMode contentMode) {
        this.contentMode = contentMode;
    }

    public Map<String, Double> getWeights() {
        return new LinkedHashMap<>(weights);
    }

    public void setWeights(Map<String, Double> weights) {
        this.weights = weights == null ? new LinkedHashMap<>() : new LinkedHashMap<>(weights);
    }

    public Map<String, Boolean> getConstraints() {
        return new LinkedHashMap<>(constraints);
    }

    public void setConstraints(Map<String, Boolean> constraints) {
        this.constraints = constraints == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(constraints);
    }

    public ProjectPrivacySettings getPrivacy() {
        return privacy.copy();
    }

    public void setPrivacy(ProjectPrivacySettings privacy) {
        this.privacy = privacy == null ? new ProjectPrivacySettings() : privacy.copy();
    }

    private static Map<String, Double> defaultWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("sharpness", 0.30);
        weights.put("eyesOpen", 0.25);
        weights.put("expression", 0.15);
        weights.put("exposure", 0.15);
        weights.put("composition", 0.10);
        weights.put("motion", 0.05);
        return weights;
    }

    private static Map<String, Boolean> defaultConstraints() {
        Map<String, Boolean> constraints = new LinkedHashMap<>();
        constraints.put("avoidSevereBlur", true);
        constraints.put("avoidSevereOverexposure", true);
        constraints.put("allowMildMotionBlur", true);
        constraints.put("preferFrontFacing", false);
        return constraints;
    }
}
