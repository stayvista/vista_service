CREATE TABLE IF NOT EXISTS ac_suggest_metric (
  type              VARCHAR(20) NOT NULL,
  canonical_id      VARCHAR(120) NOT NULL,
  impressions_7d    BIGINT NOT NULL DEFAULT 0,
  selects_7d        BIGINT NOT NULL DEFAULT 0,
  ctr_7d            DOUBLE NOT NULL DEFAULT 0,
  popularity_7d     BIGINT NOT NULL DEFAULT 0,
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (type, canonical_id),
  KEY idx_ac_suggest_popularity (popularity_7d, ctr_7d)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ac_blacklist (
  type              VARCHAR(20) NOT NULL,
  canonical_id      VARCHAR(120) NOT NULL,
  reason            VARCHAR(255) NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (type, canonical_id)
) ENGINE=InnoDB;
