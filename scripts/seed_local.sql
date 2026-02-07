-- StayVista local seed dataset (B-0603)
-- Target: fast demo/search/load-test bootstrap on local MySQL

SET @property_count := 10000;
SET @room_per_property := 3;
SET @hot_inventory_days := 365;
SET SESSION cte_max_recursion_depth = 40000;

-- Core accounts
INSERT INTO partner_account(id, name, type, status)
VALUES
  (900001, 'Seed Hotel Partner', 'HOTEL', 'ACTIVE'),
  (900002, 'Seed Ticket Partner', 'TICKET_VENDOR', 'ACTIVE')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  type = VALUES(type),
  status = VALUES(status),
  updated_at = NOW(3);

INSERT INTO user_account(id, email, phone, name, status)
VALUES (1001, 'demo.user@stayvista.local', '010-0000-0000', 'Demo User', 'ACTIVE')
ON DUPLICATE KEY UPDATE
  email = VALUES(email),
  phone = VALUES(phone),
  name = VALUES(name),
  status = VALUES(status),
  updated_at = NOW(3);

-- Properties (10,000)
WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < @property_count
)
INSERT INTO property(
  id, partner_id, name, country, city, address1, lat, lng, status, rating, thumbnail_url
)
SELECT
  100000 + n,
  900001,
  CONCAT('Seed Hotel ', LPAD(n, 5, '0')),
  'KR',
  CASE MOD(n, 4)
    WHEN 0 THEN 'Seoul'
    WHEN 1 THEN 'Busan'
    WHEN 2 THEN 'Jeju'
    ELSE 'Incheon'
  END,
  CONCAT('Demo-ro ', n),
  ROUND(37.20 + (MOD(n, 300) / 1000), 7),
  ROUND(126.80 + (MOD(n, 300) / 1000), 7),
  'ACTIVE',
  ROUND(3.5 + (MOD(n, 15) / 10), 2),
  CONCAT('https://picsum.photos/seed/stayvista-property-', n, '/640/360')
FROM seq
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  country = VALUES(country),
  city = VALUES(city),
  address1 = VALUES(address1),
  lat = VALUES(lat),
  lng = VALUES(lng),
  status = VALUES(status),
  rating = VALUES(rating),
  thumbnail_url = VALUES(thumbnail_url),
  updated_at = NOW(3);

-- Room types (30,000)
WITH RECURSIVE property_seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM property_seq WHERE n < @property_count
),
room_seq(room_no) AS (
  SELECT 1
  UNION ALL
  SELECT room_no + 1 FROM room_seq WHERE room_no < @room_per_property
)
INSERT INTO room_type(
  id, property_id, name, capacity_adults, capacity_children, bed_type, view_type, refundable, status, base_price
)
SELECT
  200000 + ((n - 1) * @room_per_property) + room_no,
  100000 + n,
  CONCAT('Room Type ', room_no),
  2 + MOD(room_no, 2),
  MOD(room_no, 2),
  CASE room_no
    WHEN 1 THEN 'DOUBLE'
    WHEN 2 THEN 'QUEEN'
    ELSE 'TWIN'
  END,
  CASE MOD(n, 3)
    WHEN 0 THEN 'CITY'
    WHEN 1 THEN 'SEA'
    ELSE 'MOUNTAIN'
  END,
  1,
  'ACTIVE',
  90000 + (MOD(n * room_no, 8) * 15000)
FROM property_seq
CROSS JOIN room_seq
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  capacity_adults = VALUES(capacity_adults),
  capacity_children = VALUES(capacity_children),
  bed_type = VALUES(bed_type),
  view_type = VALUES(view_type),
  refundable = VALUES(refundable),
  status = VALUES(status),
  base_price = VALUES(base_price),
  updated_at = NOW(3);

-- Hot-key inventory scenario: one room_type has 365-day inventory total=1000
WITH RECURSIVE day_seq(day_offset) AS (
  SELECT 0
  UNION ALL
  SELECT day_offset + 1 FROM day_seq WHERE day_offset + 1 < @hot_inventory_days
)
INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold)
SELECT
  200001,
  DATE_ADD(CURDATE(), INTERVAL day_offset DAY),
  1000,
  0,
  0
FROM day_seq
ON DUPLICATE KEY UPDATE
  total = VALUES(total),
  updated_at = NOW(3);

-- Ticket catalog + events
INSERT INTO product(id, partner_id, product_type, name, city, lat, lng, status)
VALUES (300001, 900002, 'TICKET', 'Seed City Pass', 'Seoul', 37.5665, 126.9780, 'ACTIVE')
ON DUPLICATE KEY UPDATE
  partner_id = VALUES(partner_id),
  product_type = VALUES(product_type),
  name = VALUES(name),
  city = VALUES(city),
  lat = VALUES(lat),
  lng = VALUES(lng),
  status = VALUES(status),
  updated_at = NOW(3);

WITH RECURSIVE day_seq(day_offset) AS (
  SELECT 0
  UNION ALL
  SELECT day_offset + 1 FROM day_seq WHERE day_offset < 29
),
slot_seq(slot_no) AS (
  SELECT 1
  UNION ALL
  SELECT 2
)
INSERT INTO ticket_event(id, product_id, start_time, end_time, status)
SELECT
  400000 + (day_offset * 10) + slot_no,
  300001,
  TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL day_offset DAY), IF(slot_no = 1, '10:00:00', '14:00:00')),
  DATE_ADD(
    TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL day_offset DAY), IF(slot_no = 1, '10:00:00', '14:00:00')),
    INTERVAL 120 MINUTE
  ),
  'ACTIVE'
FROM day_seq
CROSS JOIN slot_seq
ON DUPLICATE KEY UPDATE
  product_id = VALUES(product_id),
  start_time = VALUES(start_time),
  end_time = VALUES(end_time),
  status = VALUES(status),
  updated_at = NOW(3);

INSERT INTO ticket_inventory(event_id, total, hold, sold)
SELECT id, 500, 0, 0
FROM ticket_event
WHERE product_id = 300001
ON DUPLICATE KEY UPDATE
  total = VALUES(total),
  updated_at = NOW(3);

-- Package seed (accommodation + ticket)
INSERT INTO package_product(id, name, status, currency, amount_total)
VALUES (500001, 'Seed Weekend Combo', 'ACTIVE', 'KRW', 189000)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  status = VALUES(status),
  currency = VALUES(currency),
  amount_total = VALUES(amount_total),
  updated_at = NOW(3);

DELETE FROM package_product_component WHERE package_id = 500001;
INSERT INTO package_product_component(
  package_id, component_type, room_type_id, ticket_event_id, nights, rooms, quantity
)
VALUES
  (500001, 'ACCOMMODATION', 200001, NULL, 2, 1, NULL),
  (500001, 'TICKET', NULL, 400001, NULL, NULL, 1);
