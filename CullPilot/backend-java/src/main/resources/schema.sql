CREATE TABLE IF NOT EXISTS projects (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(36),
    name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    settings_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL,
    entity_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    entity_version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS user_sessions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_user_sessions_token_hash UNIQUE (token_hash)
);

CREATE TABLE IF NOT EXISTS assets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes INTEGER NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    original_path VARCHAR(500) NOT NULL,
    thumbnail_path VARCHAR(500) NOT NULL,
    analysis_status VARCHAR(20) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    recommendation VARCHAR(20) NULL,
    group_id VARCHAR(36) NULL,
    group_rank INTEGER NULL,
    recommend_score REAL NULL,
    recommend_reasons_json TEXT NULL,
    membership_reason VARCHAR(500) NULL,
    quality_json TEXT NULL,
    features_json TEXT NULL,
    exif_json TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    entity_version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_assets_project_sha256 UNIQUE (project_id, sha256)
);

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
);

CREATE TABLE IF NOT EXISTS jobs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    current_stage VARCHAR(30),
    processed_count INTEGER NOT NULL DEFAULT 0,
    total_count INTEGER NOT NULL,
    error_count INTEGER NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    idempotency_key VARCHAR(200),
    force BOOLEAN NOT NULL DEFAULT FALSE,
    rebuild_groups BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL,
    entity_version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_jobs_project_type_key UNIQUE (project_id, type, idempotency_key)
);

CREATE TABLE IF NOT EXISTS job_errors (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36),
    stage VARCHAR(30) NOT NULL,
    code VARCHAR(80) NOT NULL,
    message VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS decision_history (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    asset_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    previous_decision VARCHAR(20),
    new_decision VARCHAR(20) NOT NULL,
    source VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

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
);

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
);

-- Project settings are stored in settings_json. New settings such as
-- contentMode remain backward-compatible without changing this table.
CREATE INDEX IF NOT EXISTS idx_projects_status ON projects(status);
CREATE INDEX IF NOT EXISTS idx_projects_deleted_at ON projects(deleted_at);
CREATE INDEX IF NOT EXISTS idx_projects_created_at ON projects(created_at);
CREATE INDEX IF NOT EXISTS idx_assets_project_id ON assets(project_id);
CREATE INDEX IF NOT EXISTS idx_assets_decision ON assets(decision);
CREATE INDEX IF NOT EXISTS idx_assets_recommendation ON assets(recommendation);
CREATE INDEX IF NOT EXISTS idx_assets_analysis_status ON assets(analysis_status);
CREATE INDEX IF NOT EXISTS idx_assets_group_id ON assets(group_id);
CREATE INDEX IF NOT EXISTS idx_asset_groups_project_id ON asset_groups(project_id);
CREATE INDEX IF NOT EXISTS idx_assets_created_at ON assets(created_at);
CREATE INDEX IF NOT EXISTS idx_jobs_project_id ON jobs(project_id);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs(created_at);
CREATE INDEX IF NOT EXISTS idx_job_errors_job_id ON job_errors(job_id);
CREATE INDEX IF NOT EXISTS idx_decision_history_asset_id ON decision_history(asset_id);
CREATE INDEX IF NOT EXISTS idx_decision_history_created_at ON decision_history(created_at);
CREATE INDEX IF NOT EXISTS idx_exports_project_id ON exports(project_id);
CREATE INDEX IF NOT EXISTS idx_exports_status ON exports(status);
CREATE INDEX IF NOT EXISTS idx_aigc_edits_project_id ON aigc_edits(project_id);
CREATE INDEX IF NOT EXISTS idx_aigc_edits_asset_id ON aigc_edits(asset_id);
CREATE INDEX IF NOT EXISTS idx_aigc_edits_status ON aigc_edits(status);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_expires_at ON user_sessions(expires_at);
