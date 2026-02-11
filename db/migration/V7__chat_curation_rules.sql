CREATE TABLE IF NOT EXISTS chat_curation_rule (
    rule_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id VARCHAR(128) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    weight INT NOT NULL DEFAULT 100,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chat_curation_doc_type (doc_id, rule_type),
    KEY idx_chat_curation_enabled (enabled, rule_type, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
