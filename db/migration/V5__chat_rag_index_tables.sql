CREATE TABLE IF NOT EXISTS travel_doc (
  doc_id VARCHAR(120) PRIMARY KEY,
  source_type VARCHAR(20) NOT NULL,
  ref_id BIGINT NULL,
  city VARCHAR(100) NULL,
  title VARCHAR(255) NOT NULL,
  body TEXT NOT NULL,
  doc_hash CHAR(64) NOT NULL,
  source_updated_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_travel_doc_city_type (city, source_type),
  KEY idx_travel_doc_updated_at (updated_at),
  KEY idx_travel_doc_source_updated_at (source_type, source_updated_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS travel_doc_chunk (
  chunk_id VARCHAR(140) PRIMARY KEY,
  doc_id VARCHAR(120) NOT NULL,
  chunk_order INT NOT NULL,
  chunk_text TEXT NOT NULL,
  chunk_hash CHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_travel_doc_chunk_order (doc_id, chunk_order),
  KEY idx_travel_doc_chunk_doc (doc_id),
  CONSTRAINT fk_travel_doc_chunk_doc FOREIGN KEY (doc_id) REFERENCES travel_doc(doc_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS travel_doc_vec (
  chunk_id VARCHAR(140) NOT NULL,
  model VARCHAR(80) NOT NULL,
  vector_blob MEDIUMBLOB NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (chunk_id, model),
  KEY idx_travel_doc_vec_model (model),
  CONSTRAINT fk_travel_doc_vec_chunk FOREIGN KEY (chunk_id) REFERENCES travel_doc_chunk(chunk_id)
) ENGINE=InnoDB;
