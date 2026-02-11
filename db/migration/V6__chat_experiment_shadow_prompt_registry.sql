CREATE TABLE IF NOT EXISTS chat_prompt_template (
    template_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_key VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    system_prompt TEXT NULL,
    user_prompt_template TEXT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chat_prompt_key_version (prompt_key, version),
    KEY idx_chat_prompt_active (prompt_key, is_active, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_experiment (
    experiment_key VARCHAR(64) PRIMARY KEY,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    rollout_percent INT NOT NULL DEFAULT 0,
    treatment_model VARCHAR(128) NULL,
    prompt_version VARCHAR(64) NULL,
    parameters_json JSON NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_shadow_run (
    shadow_run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    route_primary VARCHAR(32) NOT NULL,
    route_shadow VARCHAR(32) NOT NULL,
    model_primary VARCHAR(128) NULL,
    model_shadow VARCHAR(128) NULL,
    metrics_json JSON NULL,
    error_message VARCHAR(255) NULL,
    KEY idx_chat_shadow_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_shadow_sample (
    shadow_sample_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shadow_run_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    request_redacted TEXT NOT NULL,
    response_redacted TEXT NOT NULL,
    CONSTRAINT fk_chat_shadow_sample_run
      FOREIGN KEY (shadow_run_id) REFERENCES chat_shadow_run(shadow_run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO chat_experiment (
    experiment_key,
    enabled,
    rollout_percent,
    treatment_model,
    prompt_version,
    parameters_json
) VALUES (
    'chat-core',
    0,
    0,
    NULL,
    NULL,
    JSON_OBJECT()
);
