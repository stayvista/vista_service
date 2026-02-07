ALTER TABLE booking
  ADD COLUMN confirmed_at DATETIME(3) NULL,
  ADD COLUMN cancelled_at DATETIME(3) NULL,
  ADD COLUMN expired_at DATETIME(3) NULL;

ALTER TABLE ticket_order
  ADD COLUMN confirmed_at DATETIME(3) NULL,
  ADD COLUMN cancelled_at DATETIME(3) NULL,
  ADD COLUMN expired_at DATETIME(3) NULL;

CREATE TABLE IF NOT EXISTS voucher (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  event_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
  qr_payload VARCHAR(500) NOT NULL,
  issued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  redeemed_at DATETIME(3) NULL,
  UNIQUE KEY uk_voucher_order_seq (order_id, sequence_no),
  CONSTRAINT fk_voucher_order FOREIGN KEY (order_id) REFERENCES ticket_order(id),
  CONSTRAINT fk_voucher_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT fk_voucher_event FOREIGN KEY (event_id) REFERENCES ticket_event(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS package_product (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  currency VARCHAR(10) NOT NULL DEFAULT 'KRW',
  amount_total BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS package_product_component (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  package_id BIGINT NOT NULL,
  component_type VARCHAR(30) NOT NULL,
  room_type_id BIGINT NULL,
  ticket_event_id BIGINT NULL,
  nights INT NULL,
  rooms INT NULL,
  quantity INT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_pkg_component_pkg FOREIGN KEY (package_id) REFERENCES package_product(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS package_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  package_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  booking_id BIGINT NULL,
  ticket_order_id BIGINT NULL,
  expires_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_package_order_unique (package_id, user_id, created_at),
  CONSTRAINT fk_package_order_package FOREIGN KEY (package_id) REFERENCES package_product(id),
  CONSTRAINT fk_package_order_user FOREIGN KEY (user_id) REFERENCES user_account(id)
) ENGINE=InnoDB;
