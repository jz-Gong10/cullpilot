package com.cullpilot.backend.repository.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserAppearanceRepository {

    public record Appearance(String userId, String colorId, String styleId, String source,
                             String questionnaireVersion, String onboardingStatus, Instant updatedAt) {}

    private final JdbcTemplate jdbc;

    public UserAppearanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Appearance> find(String userId) {
        List<Appearance> rows = jdbc.query("""
                SELECT user_id, color_id, style_id, source, questionnaire_version,
                       onboarding_status, updated_at
                FROM user_appearances WHERE user_id = ?
                """, (rs, row) -> new Appearance(
                rs.getString("user_id"), rs.getString("color_id"), rs.getString("style_id"),
                rs.getString("source"), rs.getString("questionnaire_version"),
                rs.getString("onboarding_status"), rs.getTimestamp("updated_at").toInstant()), userId);
        return rows.stream().findFirst();
    }

    public void ensureDefault(String userId, String colorId, String styleId) {
        jdbc.update("""
                INSERT INTO user_appearances (user_id, color_id, style_id, source,
                                              questionnaire_version, onboarding_status, updated_at)
                VALUES (?, ?, ?, 'default', NULL, 'pending', ?)
                ON CONFLICT(user_id) DO NOTHING
                """, userId, colorId, styleId, Timestamp.from(Instant.now()));
    }

    public void manualUpdate(String userId, String colorId, String styleId) {
        jdbc.update("""
                UPDATE user_appearances SET color_id = ?, style_id = ?, source = 'manual',
                    onboarding_status = 'completed', updated_at = ? WHERE user_id = ?
                """, colorId, styleId, Timestamp.from(Instant.now()), userId);
    }

    public boolean applyRecommendation(String userId, String colorId, String styleId,
                                       String version, boolean restart) {
        return jdbc.update("""
                UPDATE user_appearances SET color_id = ?, style_id = ?, source = 'onboarding',
                    questionnaire_version = ?, onboarding_status = 'completed', updated_at = ?
                WHERE user_id = ? AND (source != 'manual' OR ?)
                """, colorId, styleId, version, Timestamp.from(Instant.now()), userId, restart) == 1;
    }

    public void completeWithoutApplying(String userId) {
        jdbc.update("""
                UPDATE user_appearances SET onboarding_status = 'completed', updated_at = ?
                WHERE user_id = ?
                """, Timestamp.from(Instant.now()), userId);
    }

    public void skip(String userId) {
        jdbc.update("""
                UPDATE user_appearances SET onboarding_status = 'skipped', updated_at = ?
                WHERE user_id = ? AND onboarding_status = 'pending' AND source = 'default'
                """, Timestamp.from(Instant.now()), userId);
    }

    public void saveRecord(String userId, String version, String answersJson,
                           String scoreJson, String recommendedJson, boolean applied) {
        jdbc.update("""
                INSERT INTO appearance_questionnaire_records
                    (id, user_id, questionnaire_version, answers_json, score_json,
                     recommended_appearance_json, applied, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), userId, version, answersJson,
                scoreJson, recommendedJson, applied, Timestamp.from(Instant.now()));
    }
}
