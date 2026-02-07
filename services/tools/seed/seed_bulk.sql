-- Bulk local seed for load test and search
SET SESSION cte_max_recursion_depth = 40000;

INSERT INTO partner_account(id, name, type, status)
VALUES (1, 'Demo Hotel Partner', 'HOTEL', 'ACTIVE')
ON DUPLICATE KEY UPDATE name=name;

-- 10,000 properties
WITH RECURSIVE seq AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 10000
)
INSERT INTO property(partner_id, name, country, city, address1, lat, lng, status, rating, thumbnail_url)
SELECT
  1,
  CONCAT('Demo Property ', LPAD(n, 5, '0')),
  'KR',
  CASE
    WHEN MOD(n, 3) = 0 THEN 'Busan'
    WHEN MOD(n, 3) = 1 THEN 'Seoul'
    ELSE 'Jeju'
  END,
  CONCAT('Demo Address ', n),
  37.0 + (n * 0.0001),
  127.0 + (n * 0.0001),
  'ACTIVE',
  3.5 + (MOD(n, 15) * 0.1),
  NULL
FROM seq
WHERE NOT EXISTS (
  SELECT 1
  FROM property p
  WHERE p.name = CONCAT('Demo Property ', LPAD(seq.n, 5, '0'))
);

-- 30,000 room types (3 per property)
INSERT INTO room_type(property_id, name, capacity_adults, capacity_children, status, base_price)
SELECT
  p.id,
  CONCAT('Demo Room ', t.idx),
  2 + MOD(t.idx, 2),
  0,
  'ACTIVE',
  70000 + (t.idx * 15000) + MOD(p.id, 5000)
FROM property p
JOIN (
  SELECT 1 AS idx UNION ALL SELECT 2 UNION ALL SELECT 3
) t
LEFT JOIN room_type rt
  ON rt.property_id = p.id
 AND rt.name = CONCAT('Demo Room ', t.idx)
WHERE p.name LIKE 'Demo Property %'
  AND rt.id IS NULL;

-- hot key inventory room_type_id=2001 for 365 nights
WITH RECURSIVE days AS (
  SELECT 0 AS d
  UNION ALL
  SELECT d + 1 FROM days WHERE d < 364
)
INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold)
SELECT
  2001,
  DATE_ADD('2026-01-01', INTERVAL d DAY),
  1000,
  0,
  0
FROM days
ON DUPLICATE KEY UPDATE total = VALUES(total);
