-- ============================================
-- StayVista (Roamio) Core Schema v1 (MySQL 8)
-- Focus: concurrency-safe booking/inventory primitives
-- ============================================

-- USERS
CREATE TABLE IF NOT EXISTS user_account (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  email         VARCHAR(255) NOT NULL,
  phone         VARCHAR(50)  NULL,
  name          VARCHAR(100) NULL,
  status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_email (email)
) ENGINE=InnoDB;

-- PARTNERS (hotels, ticket vendors)
CREATE TABLE IF NOT EXISTS partner_account (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(200) NOT NULL,
  type          VARCHAR(30)  NOT NULL, -- HOTEL, TICKET_VENDOR, AGENCY
  status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

-- ACCOMMODATION CATALOG
CREATE TABLE IF NOT EXISTS property (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  partner_id    BIGINT NOT NULL,
  name          VARCHAR(255) NOT NULL,
  country       VARCHAR(100) NULL,
  city          VARCHAR(100) NULL,
  address1      VARCHAR(255) NULL,
  lat           DECIMAL(10,7) NULL,
  lng           DECIMAL(10,7) NULL,
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_property_city (city),
  CONSTRAINT fk_property_partner FOREIGN KEY (partner_id) REFERENCES partner_account(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS room_type (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id   BIGINT NOT NULL,
  name          VARCHAR(255) NOT NULL,
  capacity_adults INT NOT NULL DEFAULT 2,
  capacity_children INT NOT NULL DEFAULT 0,
  bed_type      VARCHAR(50) NULL,
  view_type     VARCHAR(50) NULL,
  refundable    TINYINT(1) NOT NULL DEFAULT 1,
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_room_type_property (property_id),
  CONSTRAINT fk_room_type_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

-- NIGHTLY INVENTORY (Room-type inventory model)
-- Concurrency-safe primitive: conditional UPDATE against (room_type_id, stay_date)
CREATE TABLE IF NOT EXISTS inventory_night (
  room_type_id  BIGINT NOT NULL,
  stay_date     DATE NOT NULL,
  total         INT NOT NULL,
  hold          INT NOT NULL DEFAULT 0,
  sold          INT NOT NULL DEFAULT 0,
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (room_type_id, stay_date),
  CONSTRAINT fk_inventory_room_type FOREIGN KEY (room_type_id) REFERENCES room_type(id)
) ENGINE=InnoDB;

-- BOOKING
CREATE TABLE IF NOT EXISTS booking (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT NOT NULL,
  property_id     BIGINT NOT NULL,
  room_type_id    BIGINT NOT NULL,
  check_in        DATE NOT NULL,
  check_out       DATE NOT NULL,
  rooms           INT NOT NULL DEFAULT 1,
  status          VARCHAR(20) NOT NULL, -- HOLD, CONFIRMED, CANCELED, EXPIRED
  expires_at      DATETIME(3) NULL,     -- for HOLD
  currency        VARCHAR(10) NOT NULL DEFAULT 'KRW',
  total_amount    BIGINT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(100) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_booking_user (user_id, created_at),
  KEY idx_booking_property_dates (property_id, check_in, check_out),
  UNIQUE KEY uk_booking_idempo (idempotency_key),
  CONSTRAINT fk_booking_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT fk_booking_property FOREIGN KEY (property_id) REFERENCES property(id),
  CONSTRAINT fk_booking_room_type FOREIGN KEY (room_type_id) REFERENCES room_type(id)
) ENGINE=InnoDB;

-- OPTIONAL: detailed per-night rows for audit/debug (not required for inventory locking)
CREATE TABLE IF NOT EXISTS booking_night (
  booking_id   BIGINT NOT NULL,
  stay_date    DATE NOT NULL,
  rooms        INT NOT NULL DEFAULT 1,
  PRIMARY KEY (booking_id, stay_date),
  KEY idx_booking_night_date (stay_date),
  CONSTRAINT fk_booking_night_booking FOREIGN KEY (booking_id) REFERENCES booking(id)
) ENGINE=InnoDB;

-- TICKET/EXPERIENCE PRODUCTS
CREATE TABLE IF NOT EXISTS product (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  partner_id    BIGINT NOT NULL,
  product_type  VARCHAR(20) NOT NULL, -- TICKET, EXPERIENCE
  name          VARCHAR(255) NOT NULL,
  city          VARCHAR(100) NULL,
  lat           DECIMAL(10,7) NULL,
  lng           DECIMAL(10,7) NULL,
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_product_city (city),
  CONSTRAINT fk_product_partner FOREIGN KEY (partner_id) REFERENCES partner_account(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ticket_event (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id    BIGINT NOT NULL,
  start_time    DATETIME(3) NOT NULL,
  end_time      DATETIME(3) NULL,
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_ticket_event_product (product_id, start_time),
  CONSTRAINT fk_ticket_event_product FOREIGN KEY (product_id) REFERENCES product(id)
) ENGINE=InnoDB;

-- Ticket inventory is a hot key (event_id). Use conditional update.
CREATE TABLE IF NOT EXISTS ticket_inventory (
  event_id       BIGINT PRIMARY KEY,
  total          INT NOT NULL,
  hold           INT NOT NULL DEFAULT 0,
  sold           INT NOT NULL DEFAULT 0,
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_ticket_inventory_event FOREIGN KEY (event_id) REFERENCES ticket_event(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ticket_order (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT NOT NULL,
  event_id        BIGINT NOT NULL,
  qty             INT NOT NULL,
  status          VARCHAR(20) NOT NULL, -- HOLD, CONFIRMED, CANCELED, EXPIRED
  expires_at      DATETIME(3) NULL,
  currency        VARCHAR(10) NOT NULL DEFAULT 'KRW',
  total_amount    BIGINT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(100) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ticket_order_idempo (idempotency_key),
  KEY idx_ticket_order_user (user_id, created_at),
  CONSTRAINT fk_ticket_order_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT fk_ticket_order_event FOREIGN KEY (event_id) REFERENCES ticket_event(id)
) ENGINE=InnoDB;

-- Idempotency store (shared)
CREATE TABLE IF NOT EXISTS idempotency_record (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  idem_key         VARCHAR(100) NOT NULL,
  scope            VARCHAR(50) NOT NULL, -- BOOKING_HOLD, BOOKING_CONFIRM, TICKET_HOLD, ...
  request_hash     CHAR(64) NOT NULL,
  status           VARCHAR(20) NOT NULL, -- IN_PROGRESS, COMPLETED, FAILED
  response_json    JSON NULL,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_idem (idem_key, scope)
) ENGINE=InnoDB;

-- Outbox
CREATE TABLE IF NOT EXISTS outbox_event (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id        CHAR(36) NOT NULL,
  aggregate_type  VARCHAR(50) NOT NULL,
  aggregate_id    VARCHAR(100) NOT NULL,
  event_type      VARCHAR(50) NOT NULL,
  payload_json    JSON NOT NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'NEW', -- NEW, PUBLISHED, FAILED
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at    DATETIME(3) NULL,
  UNIQUE KEY uk_outbox_event_id (event_id),
  KEY idx_outbox_status_created (status, created_at)
) ENGINE=InnoDB;

-- Simple POI table (geo-service may extend)
CREATE TABLE IF NOT EXISTS poi (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(255) NOT NULL,
  category      VARCHAR(50) NULL,
  city          VARCHAR(100) NULL,
  lat           DECIMAL(10,7) NOT NULL,
  lng           DECIMAL(10,7) NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_poi_city (city)
) ENGINE=InnoDB;
