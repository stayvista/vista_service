-- StayVista local seed dataset (B-0603)
-- Target: fast demo/search/load-test bootstrap on local MySQL

SET @property_count := 10000;
SET @room_per_property := 3;
SET @hot_inventory_days := 365;
SET SESSION cte_max_recursion_depth = 40000;

-- Ensure auth column exists even when Flyway migrations were not run yet
SELECT COUNT(*) INTO @has_password_hash
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'user_account'
  AND column_name = 'password_hash';
SET @add_password_hash_sql = IF(
  @has_password_hash = 0,
  'ALTER TABLE user_account ADD COLUMN password_hash VARCHAR(255) NULL AFTER email',
  'DO 0'
);
PREPARE add_password_hash_stmt FROM @add_password_hash_sql;
EXECUTE add_password_hash_stmt;
DEALLOCATE PREPARE add_password_hash_stmt;

-- Core accounts
INSERT INTO partner_account(id, name, type, status)
VALUES
  (900001, 'Roamio Hospitality Group', 'HOTEL', 'ACTIVE'),
  (900002, 'Roamio Ticket Partners', 'TICKET_VENDOR', 'ACTIVE')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  type = VALUES(type),
  status = VALUES(status),
  updated_at = NOW(3);

INSERT INTO user_account(id, email, password_hash, phone, name, status)
VALUES (
  1001,
  'demo.user@stayvista.local',
  'pbkdf2$180000$zrJdcwlhFuelrP8QuYQejQ$dcOZlouZNKPJl9xZxlfpxMCKBswsmTaimKRXa0fOU4o',
  '010-0000-0000',
  'Demo User',
  'ACTIVE'
)
ON DUPLICATE KEY UPDATE
  email = VALUES(email),
  password_hash = VALUES(password_hash),
  phone = VALUES(phone),
  name = VALUES(name),
  status = VALUES(status),
  updated_at = NOW(3);

-- Properties (10,000 across KR/JP/CN/US/EU/SEA)
INSERT INTO property(
  id, partner_id, name, country, city, address1, lat, lng, status, rating, thumbnail_url
)
WITH RECURSIVE seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < @property_count
),
name_parts AS (
  SELECT
    city_seq.n AS n,
    city_seq.city_idx,
    ELT(
      MOD(city_seq.n * 17 + FLOOR(city_seq.n / 3), 36) + 1,
      'Asteria', 'Northpoint', 'Harborline', 'Evercrest', 'Golden Laurel', 'Bluewave',
      'Summit', 'Lumin', 'Oakridge', 'Mayfield', 'Solaria', 'Riverton',
      'Grand Meridian', 'Pinehill', 'Seabreeze', 'Arden', 'Bellmont', 'Serenity',
      'Urban Nest', 'Crown Harbor', 'Velour', 'Hillford', 'The Linden', 'Azure Bay',
      'Morning Calm', 'Stonebridge', 'Lakeview', 'Marina Point', 'Cedar Grove', 'Silver Oak',
      'Westbridge', 'East Harbor', 'Maple Crest', 'The Horizon', 'Riverfront', 'Sunfield'
    ) AS brand_name,
    ELT(
      MOD(city_seq.n * 7 + FLOOR(city_seq.n / 5), 18) + 1,
      'Central', 'Riverside', 'Old Town', 'Harbor Front', 'Garden', 'Cultural',
      'Financial', 'University', 'Design', 'Lakeview', 'Hilltop', 'Marina',
      'Skyline', 'Market', 'Canal', 'Bayfront', 'Grand Park', 'Historic'
    ) AS district_name,
    ELT(
      MOD(city_seq.n * 11 + FLOOR(city_seq.n / 7), 14) + 1,
      'Hotel', 'Resort', 'Suites', 'Grand Hotel', 'Boutique Hotel', 'City Hotel',
      'Palace', 'Residence', 'Bay Hotel', 'Plaza Hotel', 'Garden Hotel', 'Sky Hotel',
      'Urban Lodge', 'Riverside Inn'
    ) AS property_type,
    CASE MOD(city_seq.n * 13 + FLOOR(city_seq.n / 11), 6)
      WHEN 0 THEN ''
      WHEN 1 THEN ' Central'
      WHEN 2 THEN ' Premier'
      WHEN 3 THEN ' Signature'
      WHEN 4 THEN ' & Spa'
      ELSE ' Downtown'
    END AS suffix_text,
    CASE city_seq.city_idx
      WHEN 0 THEN 'KR'
      WHEN 1 THEN 'KR'
      WHEN 2 THEN 'KR'
      WHEN 3 THEN 'JP'
      WHEN 4 THEN 'JP'
      WHEN 5 THEN 'JP'
      WHEN 6 THEN 'CN'
      WHEN 7 THEN 'CN'
      WHEN 8 THEN 'CN'
      WHEN 9 THEN 'US'
      WHEN 10 THEN 'US'
      WHEN 11 THEN 'US'
      WHEN 12 THEN 'GB'
      WHEN 13 THEN 'FR'
      WHEN 14 THEN 'IT'
      WHEN 15 THEN 'ES'
      WHEN 16 THEN 'DE'
      WHEN 17 THEN 'NL'
      WHEN 18 THEN 'CZ'
      WHEN 19 THEN 'CH'
      WHEN 20 THEN 'TH'
      WHEN 21 THEN 'SG'
      WHEN 22 THEN 'ID'
      WHEN 23 THEN 'VN'
      WHEN 24 THEN 'MY'
      ELSE 'PH'
    END AS country_code,
    CASE city_seq.city_idx
      WHEN 0 THEN 'Seoul'
      WHEN 1 THEN 'Busan'
      WHEN 2 THEN 'Jeju'
      WHEN 3 THEN 'Tokyo'
      WHEN 4 THEN 'Osaka'
      WHEN 5 THEN 'Kyoto'
      WHEN 6 THEN 'Beijing'
      WHEN 7 THEN 'Shanghai'
      WHEN 8 THEN 'Shenzhen'
      WHEN 9 THEN 'New York'
      WHEN 10 THEN 'Los Angeles'
      WHEN 11 THEN 'San Francisco'
      WHEN 12 THEN 'London'
      WHEN 13 THEN 'Paris'
      WHEN 14 THEN 'Rome'
      WHEN 15 THEN 'Barcelona'
      WHEN 16 THEN 'Berlin'
      WHEN 17 THEN 'Amsterdam'
      WHEN 18 THEN 'Prague'
      WHEN 19 THEN 'Zurich'
      WHEN 20 THEN 'Bangkok'
      WHEN 21 THEN 'Singapore'
      WHEN 22 THEN 'Bali'
      WHEN 23 THEN 'Ho Chi Minh City'
      WHEN 24 THEN 'Kuala Lumpur'
      ELSE 'Manila'
    END AS city_name,
    CASE city_seq.city_idx
      WHEN 0 THEN 37.5665
      WHEN 1 THEN 35.1796
      WHEN 2 THEN 33.4996
      WHEN 3 THEN 35.6762
      WHEN 4 THEN 34.6937
      WHEN 5 THEN 35.0116
      WHEN 6 THEN 39.9042
      WHEN 7 THEN 31.2304
      WHEN 8 THEN 22.5431
      WHEN 9 THEN 40.7128
      WHEN 10 THEN 34.0522
      WHEN 11 THEN 37.7749
      WHEN 12 THEN 51.5074
      WHEN 13 THEN 48.8566
      WHEN 14 THEN 41.9028
      WHEN 15 THEN 41.3874
      WHEN 16 THEN 52.5200
      WHEN 17 THEN 52.3676
      WHEN 18 THEN 50.0755
      WHEN 19 THEN 47.3769
      WHEN 20 THEN 13.7563
      WHEN 21 THEN 1.3521
      WHEN 22 THEN -8.6500
      WHEN 23 THEN 10.8231
      WHEN 24 THEN 3.1390
      ELSE 14.5995
    END AS base_lat,
    CASE city_seq.city_idx
      WHEN 0 THEN 126.9780
      WHEN 1 THEN 129.0756
      WHEN 2 THEN 126.5312
      WHEN 3 THEN 139.6503
      WHEN 4 THEN 135.5023
      WHEN 5 THEN 135.7681
      WHEN 6 THEN 116.4074
      WHEN 7 THEN 121.4737
      WHEN 8 THEN 114.0579
      WHEN 9 THEN -74.0060
      WHEN 10 THEN -118.2437
      WHEN 11 THEN -122.4194
      WHEN 12 THEN -0.1278
      WHEN 13 THEN 2.3522
      WHEN 14 THEN 12.4964
      WHEN 15 THEN 2.1686
      WHEN 16 THEN 13.4050
      WHEN 17 THEN 4.9041
      WHEN 18 THEN 14.4378
      WHEN 19 THEN 8.5417
      WHEN 20 THEN 100.5018
      WHEN 21 THEN 103.8198
      WHEN 22 THEN 115.2167
      WHEN 23 THEN 106.6297
      WHEN 24 THEN 101.6869
      ELSE 120.9842
    END AS base_lng
  FROM (
    SELECT n, MOD(n - 1, 26) AS city_idx
    FROM seq
  ) city_seq
)
SELECT
  100000 + n,
  900001,
  CASE MOD(n, 3)
    WHEN 0 THEN CONCAT(brand_name, ' ', city_name, ' ', property_type, suffix_text)
    WHEN 1 THEN CONCAT(city_name, ' ', property_type, ' by ', brand_name, suffix_text)
    ELSE CONCAT(brand_name, ' ', district_name, ' ', city_name, ' ', property_type, suffix_text)
  END,
  country_code,
  city_name,
  CONCAT(district_name, ' ', city_name, ' District ', LPAD(MOD(n * 13, 240) + 1, 3, '0')),
  ROUND(base_lat + ((MOD(n, 41) - 20) / 2500), 7),
  ROUND(base_lng + ((MOD(n * 7, 41) - 20) / 2500), 7),
  'ACTIVE',
  ROUND(3.5 + (MOD(n, 15) / 10), 2),
  CONCAT('https://picsum.photos/seed/stayvista-property-', n, '/640/360')
FROM name_parts
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
INSERT INTO room_type(
  id, property_id, name, capacity_adults, capacity_children, bed_type, view_type, refundable, status, base_price
)
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
SELECT
  200000 + ((n - 1) * @room_per_property) + room_no,
  100000 + n,
  CASE room_no
    WHEN 1 THEN 'Deluxe Double Room'
    WHEN 2 THEN 'Premier Queen Room'
    ELSE 'Family Twin Suite'
  END,
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
, room_seq
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
INSERT INTO inventory_night(room_type_id, stay_date, total, hold, sold)
WITH RECURSIVE day_seq(day_offset) AS (
  SELECT 0
  UNION ALL
  SELECT day_offset + 1 FROM day_seq WHERE day_offset + 1 < @hot_inventory_days
)
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
VALUES (300001, 900002, 'TICKET', 'Busan Coastal Explorer Pass', 'Busan', 35.1595, 129.0756, 'ACTIVE')
ON DUPLICATE KEY UPDATE
  partner_id = VALUES(partner_id),
  product_type = VALUES(product_type),
  name = VALUES(name),
  city = VALUES(city),
  lat = VALUES(lat),
  lng = VALUES(lng),
  status = VALUES(status),
  updated_at = NOW(3);

INSERT INTO ticket_event(id, product_id, start_time, end_time, status)
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
, slot_seq
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
VALUES (500001, 'Busan Weekend Explorer Package', 'ACTIVE', 'KRW', 189000)
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

-- Nearby POI seed for geo/chat testing (12,000 rows, global)
SET @poi_count := 12000;

INSERT INTO poi(
  id, name, category, city, lat, lng, address, description,
  popularity_score, rating_score, active
)
WITH RECURSIVE poi_seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM poi_seq WHERE n < @poi_count
),
poi_city AS (
  SELECT
    poi_seq.n AS n,
    MOD(poi_seq.n - 1, 26) AS city_idx,
    CASE MOD(poi_seq.n - 1, 26)
      WHEN 0 THEN 'Seoul'
      WHEN 1 THEN 'Busan'
      WHEN 2 THEN 'Jeju'
      WHEN 3 THEN 'Tokyo'
      WHEN 4 THEN 'Osaka'
      WHEN 5 THEN 'Kyoto'
      WHEN 6 THEN 'Beijing'
      WHEN 7 THEN 'Shanghai'
      WHEN 8 THEN 'Shenzhen'
      WHEN 9 THEN 'New York'
      WHEN 10 THEN 'Los Angeles'
      WHEN 11 THEN 'San Francisco'
      WHEN 12 THEN 'London'
      WHEN 13 THEN 'Paris'
      WHEN 14 THEN 'Rome'
      WHEN 15 THEN 'Barcelona'
      WHEN 16 THEN 'Berlin'
      WHEN 17 THEN 'Amsterdam'
      WHEN 18 THEN 'Prague'
      WHEN 19 THEN 'Zurich'
      WHEN 20 THEN 'Bangkok'
      WHEN 21 THEN 'Singapore'
      WHEN 22 THEN 'Bali'
      WHEN 23 THEN 'Ho Chi Minh City'
      WHEN 24 THEN 'Kuala Lumpur'
      ELSE 'Manila'
    END AS city_name,
    CASE MOD(poi_seq.n - 1, 26)
      WHEN 0 THEN 'Korea'
      WHEN 1 THEN 'Korea'
      WHEN 2 THEN 'Korea'
      WHEN 3 THEN 'Japan'
      WHEN 4 THEN 'Japan'
      WHEN 5 THEN 'Japan'
      WHEN 6 THEN 'China'
      WHEN 7 THEN 'China'
      WHEN 8 THEN 'China'
      WHEN 9 THEN 'United States'
      WHEN 10 THEN 'United States'
      WHEN 11 THEN 'United States'
      WHEN 12 THEN 'United Kingdom'
      WHEN 13 THEN 'France'
      WHEN 14 THEN 'Italy'
      WHEN 15 THEN 'Spain'
      WHEN 16 THEN 'Germany'
      WHEN 17 THEN 'Netherlands'
      WHEN 18 THEN 'Czech Republic'
      WHEN 19 THEN 'Switzerland'
      WHEN 20 THEN 'Thailand'
      WHEN 21 THEN 'Singapore'
      WHEN 22 THEN 'Indonesia'
      WHEN 23 THEN 'Vietnam'
      WHEN 24 THEN 'Malaysia'
      ELSE 'Philippines'
    END AS country_name,
    CASE MOD(poi_seq.n - 1, 26)
      WHEN 0 THEN 37.5665
      WHEN 1 THEN 35.1796
      WHEN 2 THEN 33.4996
      WHEN 3 THEN 35.6762
      WHEN 4 THEN 34.6937
      WHEN 5 THEN 35.0116
      WHEN 6 THEN 39.9042
      WHEN 7 THEN 31.2304
      WHEN 8 THEN 22.5431
      WHEN 9 THEN 40.7128
      WHEN 10 THEN 34.0522
      WHEN 11 THEN 37.7749
      WHEN 12 THEN 51.5074
      WHEN 13 THEN 48.8566
      WHEN 14 THEN 41.9028
      WHEN 15 THEN 41.3874
      WHEN 16 THEN 52.5200
      WHEN 17 THEN 52.3676
      WHEN 18 THEN 50.0755
      WHEN 19 THEN 47.3769
      WHEN 20 THEN 13.7563
      WHEN 21 THEN 1.3521
      WHEN 22 THEN -8.6500
      WHEN 23 THEN 10.8231
      WHEN 24 THEN 3.1390
      ELSE 14.5995
    END AS base_lat,
    CASE MOD(poi_seq.n - 1, 26)
      WHEN 0 THEN 126.9780
      WHEN 1 THEN 129.0756
      WHEN 2 THEN 126.5312
      WHEN 3 THEN 139.6503
      WHEN 4 THEN 135.5023
      WHEN 5 THEN 135.7681
      WHEN 6 THEN 116.4074
      WHEN 7 THEN 121.4737
      WHEN 8 THEN 114.0579
      WHEN 9 THEN -74.0060
      WHEN 10 THEN -118.2437
      WHEN 11 THEN -122.4194
      WHEN 12 THEN -0.1278
      WHEN 13 THEN 2.3522
      WHEN 14 THEN 12.4964
      WHEN 15 THEN 2.1686
      WHEN 16 THEN 13.4050
      WHEN 17 THEN 4.9041
      WHEN 18 THEN 14.4378
      WHEN 19 THEN 8.5417
      WHEN 20 THEN 100.5018
      WHEN 21 THEN 103.8198
      WHEN 22 THEN 115.2167
      WHEN 23 THEN 106.6297
      WHEN 24 THEN 101.6869
      ELSE 120.9842
    END AS base_lng
  FROM poi_seq
)
SELECT
  600000 + n,
  CONCAT(
    city_name,
    ' ',
    ELT(
      MOD(n * 5 + FLOOR(n / 7), 16) + 1,
      'Skyline', 'Riverfront', 'Central', 'Heritage', 'Harbor', 'Old Quarter', 'Garden', 'Cultural',
      'Market', 'Arts', 'Canal', 'Bay', 'Summit', 'Palace', 'Marina', 'Discovery'
    ),
    ' ',
    CASE MOD(n, 4)
      WHEN 0 THEN 'Attraction'
      WHEN 1 THEN 'Food Spot'
      WHEN 2 THEN 'Shopping Hub'
      ELSE 'Museum'
    END
  ),
  CASE MOD(n, 4)
    WHEN 0 THEN 'attraction'
    WHEN 1 THEN 'food'
    WHEN 2 THEN 'shopping'
    ELSE 'museum'
  END,
  city_name,
  ROUND(base_lat + ((MOD(n, 49) - 24) / 2000), 7),
  ROUND(base_lng + ((MOD(n * 13, 49) - 24) / 2000), 7),
  CONCAT(
    city_name,
    ', ',
    country_name,
    ' ',
    ELT(
      MOD(n * 7 + FLOOR(n / 5), 10) + 1,
      'Central', 'Old Town', 'Waterfront', 'Arts Quarter', 'Business District',
      'Heritage Zone', 'Garden District', 'Market Street', 'Cultural Mile', 'Harbor Side'
    ),
    ' Area'
  ),
  CONCAT(
    'Editorial pick #',
    n,
    '. Popular ',
    CASE MOD(n, 4)
      WHEN 0 THEN 'attractions'
      WHEN 1 THEN 'food spots'
      WHEN 2 THEN 'shopping areas'
      ELSE 'museums'
    END,
    ' in ',
    city_name,
    '.'
  ),
  80 + MOD(n * 19, 920),
  ROUND(3.4 + (MOD(n, 16) * 0.1), 2),
  CASE WHEN MOD(n, 43) = 0 THEN 0 ELSE 1 END
FROM poi_city
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  category = VALUES(category),
  city = VALUES(city),
  lat = VALUES(lat),
  lng = VALUES(lng),
  address = VALUES(address),
  description = VALUES(description),
  popularity_score = VALUES(popularity_score),
  rating_score = VALUES(rating_score),
  active = VALUES(active);
