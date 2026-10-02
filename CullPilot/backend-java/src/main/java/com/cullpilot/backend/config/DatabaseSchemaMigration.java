package com.cullpilot.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
public class DatabaseSchemaMigration {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void migrate() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_appearances (
                    user_id VARCHAR(36) NOT NULL PRIMARY KEY,
                    color_id VARCHAR(3) NOT NULL,
                    style_id VARCHAR(30) NOT NULL,
                    source VARCHAR(20) NOT NULL,
                    questionnaire_version VARCHAR(40),
                    onboarding_status VARCHAR(20) NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS appearance_questionnaire_records (
                    id VARCHAR(36) NOT NULL PRIMARY KEY,
                    user_id VARCHAR(36) NOT NULL,
                    questionnaire_version VARCHAR(40) NOT NULL,
                    answers_json TEXT NOT NULL,
                    score_json TEXT NOT NULL,
                    recommended_appearance_json TEXT NOT NULL,
                    applied BOOLEAN NOT NULL,
                    completed_at TIMESTAMP NOT NULL
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_appearance_records_user_id ON appearance_questionnaire_records(user_id)");
        Integer ownerColumnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('projects') WHERE name = 'owner_id'",
                Integer.class);
        if (ownerColumnCount != null && ownerColumnCount == 0) {
            jdbcTemplate.execute("ALTER TABLE projects ADD COLUMN owner_id VARCHAR(36)");
        }
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_projects_owner_id ON projects(owner_id)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS decision_history (
                    id VARCHAR(36) NOT NULL PRIMARY KEY,
                    asset_id VARCHAR(36) NOT NULL,
                    user_id VARCHAR(36) NOT NULL,
                    previous_decision VARCHAR(20),
                    new_decision VARCHAR(20) NOT NULL,
                    source VARCHAR(20) NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_decision_history_asset_id ON decision_history(asset_id)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS exports (
                    id VARCHAR(36) NOT NULL PRIMARY KEY,
                    project_id VARCHAR(36) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    selection VARCHAR(30) NOT NULL,
                    copy_images BOOLEAN NOT NULL,
                    strip_gps BOOLEAN NOT NULL,
                    include_manifest BOOLEAN NOT NULL,
                    processed_count INTEGER NOT NULL DEFAULT 0,
                    total_count INTEGER NOT NULL DEFAULT 0,
                    file_path VARCHAR(500),
                    error_message VARCHAR(500),
                    created_at TIMESTAMP NOT NULL,
                    finished_at TIMESTAMP NULL,
                    entity_version INTEGER NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_exports_project_id ON exports(project_id)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS aigc_edits (
                    id VARCHAR(36) NOT NULL PRIMARY KEY,
                    project_id VARCHAR(36) NOT NULL,
                    asset_id VARCHAR(36) NOT NULL,
                    prompt_text TEXT NOT NULL,
                    prompt_used TEXT,
                    model VARCHAR(100) NOT NULL,
                    image_size VARCHAR(20) NOT NULL,
                    prompt_extend BOOLEAN NOT NULL,
                    watermark BOOLEAN NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    provider VARCHAR(50),
                    generated_path VARCHAR(500),
                    mime_type VARCHAR(100),
                    size_bytes INTEGER,
                    error_message VARCHAR(500),
                    created_at TIMESTAMP NOT NULL,
                    started_at TIMESTAMP,
                    finished_at TIMESTAMP,
                    entity_version INTEGER NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_aigc_edits_project_id ON aigc_edits(project_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_aigc_edits_asset_id ON aigc_edits(asset_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_aigc_edits_status ON aigc_edits(status)");
        String[][] columns = {
                {"group_rank", "INTEGER"},
                {"recommend_score", "REAL"},
                {"recommend_reasons_json", "TEXT"},
                {"membership_reason", "VARCHAR(500)"},
                {"quality_json", "TEXT"},
                {"features_json", "TEXT"},
                {"exif_json", "TEXT"}
        };
        for (String[] column : columns) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pragma_table_info('assets') WHERE name = ?",
                    Integer.class, column[0]);
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE assets ADD COLUMN " + column[0] + " " + column[1]);
            }
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS asset_groups (
                    id VARCHAR(36) NOT NULL PRIMARY KEY,
                    project_id VARCHAR(36) NOT NULL,
                    group_no INTEGER NOT NULL,
                    group_type VARCHAR(40) NOT NULL,
                    has_face BOOLEAN NOT NULL,
                    asset_count INTEGER NOT NULL,
                    confidence REAL NOT NULL,
                    average_similarity REAL NOT NULL,
                    max_distance REAL NOT NULL,
                    reasons_json TEXT NOT NULL,
                    recommended_asset_ids_json TEXT NOT NULL,
                    time_from TIMESTAMP NULL,
                    time_to TIMESTAMP NULL
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_asset_groups_project_id ON asset_groups(project_id)");
    }
}
