CREATE TABLE IF NOT EXISTS property_review (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id BIGINT NOT NULL,
  reviewer_name VARCHAR(120) NOT NULL,
  reviewer_country VARCHAR(2) NULL,
  traveler_type VARCHAR(80) NOT NULL DEFAULT '나홀로 여행객',
  stay_date DATE NOT NULL,
  nights INT NOT NULL DEFAULT 1,
  score_overall DECIMAL(3,1) NOT NULL,
  score_service DECIMAL(3,1) NULL,
  score_cleanliness DECIMAL(3,1) NULL,
  score_facility DECIMAL(3,1) NULL,
  score_value DECIMAL(3,1) NULL,
  score_location DECIMAL(3,1) NULL,
  title VARCHAR(255) NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_property_review_lookup (property_id, status, stay_date DESC, id DESC),
  KEY idx_property_review_score (property_id, score_overall),
  CONSTRAINT fk_property_review_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_review_tag (
  review_id BIGINT NOT NULL,
  tag VARCHAR(60) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (review_id, tag),
  KEY idx_property_review_tag_tag (tag, review_id),
  CONSTRAINT fk_property_review_tag_review FOREIGN KEY (review_id) REFERENCES property_review(id) ON DELETE CASCADE
) ENGINE=InnoDB;
