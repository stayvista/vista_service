ALTER TABLE property
  ADD COLUMN district_name VARCHAR(120) NULL,
  ADD COLUMN star_rating INT NOT NULL DEFAULT 4,
  ADD COLUMN location_rating DECIMAL(3,2) NOT NULL DEFAULT 0.0,
  ADD COLUMN popularity_score INT NOT NULL DEFAULT 0,
  ADD COLUMN property_type_code VARCHAR(40) NULL;

CREATE INDEX idx_property_city_district ON property(city, district_name);
CREATE INDEX idx_property_city_star ON property(city, star_rating);
CREATE INDEX idx_property_city_type ON property(city, property_type_code);

CREATE TABLE IF NOT EXISTS district (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  city          VARCHAR(100) NOT NULL,
  name          VARCHAR(120) NOT NULL,
  blurb         VARCHAR(255) NULL,
  rank_score    INT NOT NULL DEFAULT 0,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_district_city_name (city, name),
  KEY idx_district_city_rank (city, rank_score)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS city_poi_popular (
  city          VARCHAR(100) NOT NULL,
  poi_id        BIGINT NOT NULL,
  rank_score    INT NOT NULL DEFAULT 0,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (city, poi_id),
  KEY idx_city_poi_rank (city, rank_score),
  CONSTRAINT fk_city_poi_popular_poi FOREIGN KEY (poi_id) REFERENCES poi(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS city_featured_property (
  city          VARCHAR(100) NOT NULL,
  property_id   BIGINT NOT NULL,
  rank_score    INT NOT NULL DEFAULT 0,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (city, property_id),
  KEY idx_city_featured_rank (city, rank_score),
  CONSTRAINT fk_city_featured_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS session_locale (
  session_id    VARCHAR(120) PRIMARY KEY,
  country       VARCHAR(10) NOT NULL,
  currency      VARCHAR(10) NOT NULL,
  language      VARCHAR(10) NOT NULL,
  source        VARCHAR(20) NOT NULL,
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS user_locale (
  user_id       BIGINT PRIMARY KEY,
  country       VARCHAR(10) NOT NULL,
  currency      VARCHAR(10) NOT NULL,
  language      VARCHAR(10) NOT NULL,
  source        VARCHAR(20) NOT NULL,
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_user_locale_user FOREIGN KEY (user_id) REFERENCES user_account(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fx_rate (
  base          VARCHAR(10) NOT NULL,
  quote         VARCHAR(10) NOT NULL,
  rate          DECIMAL(18,8) NOT NULL,
  as_of         DATETIME(3) NOT NULL,
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (base, quote)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS city_day_min_price (
  city          VARCHAR(100) NOT NULL,
  stay_date     DATE NOT NULL,
  min_price_krw BIGINT NOT NULL,
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (city, stay_date),
  KEY idx_city_day_price (city, min_price_krw)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_type (
  code          VARCHAR(40) PRIMARY KEY,
  label_ko      VARCHAR(100) NOT NULL,
  label_en      VARCHAR(100) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS amenity (
  code          VARCHAR(40) PRIMARY KEY,
  label_ko      VARCHAR(100) NOT NULL,
  label_en      VARCHAR(100) NOT NULL,
  group_code    VARCHAR(40) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payment_option (
  code          VARCHAR(40) PRIMARY KEY,
  label_ko      VARCHAR(100) NOT NULL,
  group_code    VARCHAR(40) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS theme (
  code          VARCHAR(40) PRIMARY KEY,
  label_ko      VARCHAR(100) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS brand (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(120) NOT NULL,
  UNIQUE KEY uk_brand_name (name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_amenity (
  property_id   BIGINT NOT NULL,
  amenity_code  VARCHAR(40) NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (property_id, amenity_code),
  KEY idx_property_amenity_code (amenity_code),
  CONSTRAINT fk_property_amenity_property FOREIGN KEY (property_id) REFERENCES property(id),
  CONSTRAINT fk_property_amenity_amenity FOREIGN KEY (amenity_code) REFERENCES amenity(code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_brand (
  property_id   BIGINT NOT NULL,
  brand_id      BIGINT NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (property_id, brand_id),
  KEY idx_property_brand_brand (brand_id),
  CONSTRAINT fk_property_brand_property FOREIGN KEY (property_id) REFERENCES property(id),
  CONSTRAINT fk_property_brand_brand FOREIGN KEY (brand_id) REFERENCES brand(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_theme (
  property_id   BIGINT NOT NULL,
  theme_code    VARCHAR(40) NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (property_id, theme_code),
  KEY idx_property_theme_code (theme_code),
  CONSTRAINT fk_property_theme_property FOREIGN KEY (property_id) REFERENCES property(id),
  CONSTRAINT fk_property_theme_theme FOREIGN KEY (theme_code) REFERENCES theme(code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_payment_option (
  property_id            BIGINT NOT NULL,
  payment_option_code    VARCHAR(40) NOT NULL,
  created_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (property_id, payment_option_code),
  KEY idx_property_payment_code (payment_option_code),
  CONSTRAINT fk_property_payment_property FOREIGN KEY (property_id) REFERENCES property(id),
  CONSTRAINT fk_property_payment_option FOREIGN KEY (payment_option_code) REFERENCES payment_option(code)
) ENGINE=InnoDB;

INSERT IGNORE INTO property_type(code, label_ko, label_en) VALUES
  ('hotel', '호텔', 'Hotel'),
  ('resort', '리조트', 'Resort'),
  ('boutique', '부티크', 'Boutique'),
  ('villa', '빌라', 'Villa'),
  ('guesthouse', '게스트하우스', 'Guesthouse');

INSERT IGNORE INTO amenity(code, label_ko, label_en, group_code) VALUES
  ('wifi', '와이파이', 'Wi-Fi', 'essential'),
  ('breakfast', '조식', 'Breakfast', 'dining'),
  ('pool', '수영장', 'Pool', 'wellness'),
  ('gym', '피트니스', 'Gym', 'wellness'),
  ('parking', '주차', 'Parking', 'essential'),
  ('spa', '스파', 'Spa', 'wellness'),
  ('ocean_view', '오션뷰', 'Ocean View', 'view'),
  ('kitchen', '주방', 'Kitchen', 'room');

INSERT IGNORE INTO payment_option(code, label_ko, group_code) VALUES
  ('pay_now', '지금 결제', 'timing'),
  ('pay_later', '숙소 결제', 'timing'),
  ('free_cancel', '무료 취소', 'policy'),
  ('no_prepay', '선결제 없음', 'policy');

INSERT IGNORE INTO theme(code, label_ko) VALUES
  ('family', '가족여행'),
  ('business', '비즈니스'),
  ('romance', '커플'),
  ('nature', '자연'),
  ('shopping', '쇼핑');

INSERT IGNORE INTO fx_rate(base, quote, rate, as_of) VALUES
  ('KRW', 'KRW', 1.00000000, NOW(3)),
  ('USD', 'USD', 1.00000000, NOW(3)),
  ('JPY', 'JPY', 1.00000000, NOW(3)),
  ('EUR', 'EUR', 1.00000000, NOW(3)),
  ('USD', 'KRW', 1320.00000000, NOW(3)),
  ('KRW', 'USD', 0.00075758, NOW(3)),
  ('JPY', 'KRW', 8.80000000, NOW(3)),
  ('KRW', 'JPY', 0.11363636, NOW(3)),
  ('EUR', 'KRW', 1430.00000000, NOW(3)),
  ('KRW', 'EUR', 0.00069930, NOW(3)),
  ('USD', 'JPY', 149.50000000, NOW(3)),
  ('JPY', 'USD', 0.00668896, NOW(3)),
  ('USD', 'EUR', 0.92000000, NOW(3)),
  ('EUR', 'USD', 1.08695652, NOW(3));
