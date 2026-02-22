CREATE TABLE IF NOT EXISTS home_hero (
  id                  TINYINT PRIMARY KEY,
  eyebrow_text        VARCHAR(120) NOT NULL,
  title_text          VARCHAR(180) NOT NULL,
  summary_text        VARCHAR(400) NOT NULL,
  background_image_url VARCHAR(500) NULL,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS home_hero_metric (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  hero_id             TINYINT NOT NULL,
  metric_value        VARCHAR(60) NOT NULL,
  metric_label        VARCHAR(120) NOT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_home_hero_metric (hero_id, active, display_order),
  CONSTRAINT fk_home_hero_metric_hero FOREIGN KEY (hero_id) REFERENCES home_hero(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS home_quick_filter (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  label               VARCHAR(80) NOT NULL,
  filter_key          VARCHAR(40) NOT NULL,
  filter_value        VARCHAR(80) NOT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_home_quick_filter (active, display_order)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS home_destination_card (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  section_code        VARCHAR(20) NOT NULL,
  city                VARCHAR(120) NOT NULL,
  country             VARCHAR(10) NULL,
  label               VARCHAR(120) NOT NULL,
  image_url           VARCHAR(500) NULL,
  highlights          VARCHAR(220) NULL,
  property_count      INT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_home_destination_section_city (section_code, city),
  KEY idx_home_destination_section (section_code, active, display_order)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS promotion_section (
  section_code        VARCHAR(40) PRIMARY KEY,
  title               VARCHAR(120) NOT NULL,
  subtitle            VARCHAR(220) NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_editorial (
  property_id             BIGINT PRIMARY KEY,
  short_description       TEXT NULL,
  long_description        TEXT NULL,
  check_in_time           VARCHAR(10) NULL,
  check_out_time          VARCHAR(10) NULL,
  airport_transfer_fee_krw BIGINT NULL,
  breakfast_fee_krw       BIGINT NULL,
  remodeled_year          INT NULL,
  children_policy         VARCHAR(220) NULL,
  created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_property_editorial_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_highlight (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id         BIGINT NOT NULL,
  content             VARCHAR(255) NOT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_property_highlight (property_id, active, display_order),
  CONSTRAINT fk_property_highlight_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_gallery_image (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id         BIGINT NOT NULL,
  image_url           VARCHAR(500) NOT NULL,
  is_cover            TINYINT(1) NOT NULL DEFAULT 0,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_property_gallery (property_id, active, is_cover, display_order),
  CONSTRAINT fk_property_gallery_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_staycation_card (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id         BIGINT NOT NULL,
  card_code           VARCHAR(40) NOT NULL,
  title               VARCHAR(100) NOT NULL,
  subtitle            VARCHAR(200) NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_property_staycation_card (property_id, card_code),
  KEY idx_property_staycation_card (property_id, active, display_order),
  CONSTRAINT fk_property_staycation_card_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_staycation_item (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  card_id             BIGINT NOT NULL,
  item_text           VARCHAR(160) NOT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_property_staycation_item (card_id, active, display_order),
  CONSTRAINT fk_property_staycation_item_card FOREIGN KEY (card_id) REFERENCES property_staycation_card(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS room_type_media (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_type_id        BIGINT NOT NULL,
  image_url           VARCHAR(500) NOT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_room_type_media (room_type_id, active, display_order),
  CONSTRAINT fk_room_type_media_room_type FOREIGN KEY (room_type_id) REFERENCES room_type(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS room_type_feature (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_type_id        BIGINT NOT NULL,
  feature_text        VARCHAR(160) NOT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_room_type_feature (room_type_id, active, display_order),
  CONSTRAINT fk_room_type_feature_room_type FOREIGN KEY (room_type_id) REFERENCES room_type(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS room_rate_plan (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_type_id        BIGINT NOT NULL,
  plan_code           VARCHAR(60) NOT NULL,
  occupancy_text      VARCHAR(120) NULL,
  pay_summary         VARCHAR(120) NULL,
  urgency_text        VARCHAR(120) NULL,
  list_price_krw      BIGINT NOT NULL,
  sale_price_krw      BIGINT NOT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_room_rate_plan_code (room_type_id, plan_code),
  KEY idx_room_rate_plan (room_type_id, active, display_order),
  CONSTRAINT fk_room_rate_plan_room_type FOREIGN KEY (room_type_id) REFERENCES room_type(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS room_rate_plan_benefit (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id             BIGINT NOT NULL,
  benefit_text        VARCHAR(160) NOT NULL,
  display_order       INT NOT NULL DEFAULT 0,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_room_rate_plan_benefit (plan_id, active, display_order),
  CONSTRAINT fk_room_rate_plan_benefit_plan FOREIGN KEY (plan_id) REFERENCES room_rate_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB;

SET @has_product_image_url = (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'product'
    AND column_name = 'image_url'
);
SET @add_product_image_url_sql = IF(
  @has_product_image_url = 0,
  'ALTER TABLE product ADD COLUMN image_url VARCHAR(500) NULL',
  'DO 0'
);
PREPARE add_product_image_url_stmt FROM @add_product_image_url_sql;
EXECUTE add_product_image_url_stmt;
DEALLOCATE PREPARE add_product_image_url_stmt;

SET @has_package_image_url = (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'package_product'
    AND column_name = 'image_url'
);
SET @add_package_image_url_sql = IF(
  @has_package_image_url = 0,
  'ALTER TABLE package_product ADD COLUMN image_url VARCHAR(500) NULL',
  'DO 0'
);
PREPARE add_package_image_url_stmt FROM @add_package_image_url_sql;
EXECUTE add_package_image_url_stmt;
DEALLOCATE PREPARE add_package_image_url_stmt;

INSERT INTO promotion_section(section_code, title, subtitle, display_order, active)
VALUES
  ('HOTEL_SALE', '숙소 세일', '기간 한정 쿠폰 발급', 10, 1),
  ('ACTIVITY_PROMO', '즐길 거리 프로모션', '티켓/패키지 특가 혜택', 20, 1),
  ('RECOMMENDED_STAY', '추천 숙소', '브랜드 제휴 할인 카드', 30, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  subtitle = VALUES(subtitle),
  display_order = VALUES(display_order),
  active = VALUES(active),
  updated_at = NOW(3);
