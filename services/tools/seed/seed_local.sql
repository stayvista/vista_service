-- Minimal local seed
INSERT INTO partner_account(id, name, type, status)
VALUES (1, 'Demo Hotel Partner', 'HOTEL', 'ACTIVE')
ON DUPLICATE KEY UPDATE name=name;

INSERT INTO partner_account(id, name, type, status)
VALUES (2, 'Demo Ticket Partner', 'TICKET_VENDOR', 'ACTIVE')
ON DUPLICATE KEY UPDATE name=name;

INSERT INTO user_account(id, email, name, status)
VALUES (1001, 'demo-user@local.test', 'Demo User', 'ACTIVE')
ON DUPLICATE KEY UPDATE name=name;

INSERT INTO property(id, partner_id, name, country, city, address1, lat, lng, status, rating, thumbnail_url)
VALUES (1001, 1, 'Wanderly Hotel Seoul', 'KR', 'Seoul', 'Teheran-ro', 37.5010000, 127.0396000, 'ACTIVE', 4.4, NULL)
ON DUPLICATE KEY UPDATE name=name;

INSERT INTO room_type(id, property_id, name, capacity_adults, capacity_children, status, base_price)
VALUES (2001, 1001, 'Standard Double', 2, 0, 'ACTIVE', 120000)
ON DUPLICATE KEY UPDATE name=name;

INSERT INTO product(id, partner_id, product_type, name, city, status)
VALUES (4001, 2, 'ATTRACTION', 'Wanderly City Pass', 'Seoul', 'ACTIVE')
ON DUPLICATE KEY UPDATE name=name;

INSERT INTO ticket_event(id, product_id, start_time, end_time, status)
VALUES (5001, 4001, '2026-03-01 10:00:00', '2026-03-01 18:00:00', 'ACTIVE')
ON DUPLICATE KEY UPDATE status='ACTIVE';

INSERT INTO ticket_inventory(event_id, total, hold, sold)
VALUES (5001, 1000, 0, 0)
ON DUPLICATE KEY UPDATE total=VALUES(total);

INSERT INTO poi(id, name, category, city, lat, lng)
VALUES
  (9001, 'City Museum', 'museum', 'Seoul', 37.5000000, 127.0400000),
  (9002, 'River Park', 'attraction', 'Seoul', 37.4985000, 127.0350000),
  (9003, 'Local Food Alley', 'food', 'Seoul', 37.5030000, 127.0415000)
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- hot key inventory for 365 days
WITH RECURSIVE seq AS (
  SELECT 0 AS d
  UNION ALL
  SELECT d + 1 FROM seq WHERE d < 364
)
INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold)
SELECT 2001, DATE_ADD('2026-01-01', INTERVAL d DAY), 1000, 0, 0
FROM seq
ON DUPLICATE KEY UPDATE total=VALUES(total);
