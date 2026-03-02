CREATE TABLE IF NOT EXISTS customer_inquiry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  inquiry_type VARCHAR(50) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  answer_content TEXT NULL,
  answered_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_customer_inquiry_user_created (user_id, created_at),
  KEY idx_customer_inquiry_user_status (user_id, status, created_at),
  CONSTRAINT fk_customer_inquiry_user FOREIGN KEY (user_id) REFERENCES user_account(id)
) ENGINE=InnoDB;
