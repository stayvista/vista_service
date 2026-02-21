-- StayVista local seed dataset (B-0603)
-- Target: fast demo/search/load-test bootstrap on local MySQL

SET @property_count := 20000;
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

-- Properties (20,000 across KR/JP/CN/US/EU/SEA/ME/OCEANIA)
INSERT INTO property(
  id, partner_id, name, country, city, district_name, address1, lat, lng, status,
  rating, star_rating, location_rating, popularity_score, property_type_code, thumbnail_url
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
      WHEN 25 THEN 'PH'
      WHEN 26 THEN 'AE'
      WHEN 27 THEN 'AE'
      WHEN 28 THEN 'TR'
      WHEN 29 THEN 'AT'
      WHEN 30 THEN 'PT'
      WHEN 31 THEN 'GR'
      WHEN 32 THEN 'AU'
      WHEN 33 THEN 'AU'
      WHEN 34 THEN 'NZ'
      WHEN 35 THEN 'CA'
      WHEN 36 THEN 'CA'
      WHEN 37 THEN 'US'
      WHEN 38 THEN 'US'
      ELSE 'US'
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
      WHEN 25 THEN 'Manila'
      WHEN 26 THEN 'Dubai'
      WHEN 27 THEN 'Abu Dhabi'
      WHEN 28 THEN 'Istanbul'
      WHEN 29 THEN 'Vienna'
      WHEN 30 THEN 'Lisbon'
      WHEN 31 THEN 'Athens'
      WHEN 32 THEN 'Sydney'
      WHEN 33 THEN 'Melbourne'
      WHEN 34 THEN 'Auckland'
      WHEN 35 THEN 'Toronto'
      WHEN 36 THEN 'Vancouver'
      WHEN 37 THEN 'Chicago'
      WHEN 38 THEN 'Seattle'
      ELSE 'Las Vegas'
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
      WHEN 25 THEN 14.5995
      WHEN 26 THEN 25.2048
      WHEN 27 THEN 24.4539
      WHEN 28 THEN 41.0082
      WHEN 29 THEN 48.2082
      WHEN 30 THEN 38.7223
      WHEN 31 THEN 37.9838
      WHEN 32 THEN -33.8688
      WHEN 33 THEN -37.8136
      WHEN 34 THEN -36.8509
      WHEN 35 THEN 43.6532
      WHEN 36 THEN 49.2827
      WHEN 37 THEN 41.8781
      WHEN 38 THEN 47.6062
      ELSE 36.1699
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
      WHEN 25 THEN 120.9842
      WHEN 26 THEN 55.2708
      WHEN 27 THEN 54.3773
      WHEN 28 THEN 28.9784
      WHEN 29 THEN 16.3738
      WHEN 30 THEN -9.1393
      WHEN 31 THEN 23.7275
      WHEN 32 THEN 151.2093
      WHEN 33 THEN 144.9631
      WHEN 34 THEN 174.7645
      WHEN 35 THEN -79.3832
      WHEN 36 THEN -123.1207
      WHEN 37 THEN -87.6298
      WHEN 38 THEN -122.3321
      ELSE -115.1398
    END AS base_lng
  FROM (
    SELECT n, MOD(n - 1, 40) AS city_idx
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
  district_name,
  CONCAT(district_name, ' ', city_name, ' District ', LPAD(MOD(n * 13, 240) + 1, 3, '0')),
  ROUND(base_lat + ((MOD(n, 41) - 20) / 2500), 7),
  ROUND(base_lng + ((MOD(n * 7, 41) - 20) / 2500), 7),
  'ACTIVE',
  ROUND(3.5 + (MOD(n, 15) / 10), 2),
  LEAST(5, GREATEST(2, FLOOR(3 + MOD(n, 3) + (MOD(n, 5) / 4)))),
  ROUND(3.2 + (MOD(n * 7, 15) / 10), 2),
  100 + MOD(n * 17, 900),
  CASE MOD(n, 5)
    WHEN 0 THEN 'hotel'
    WHEN 1 THEN 'resort'
    WHEN 2 THEN 'boutique'
    WHEN 3 THEN 'villa'
    ELSE 'guesthouse'
  END,
  CONCAT('https://picsum.photos/seed/stayvista-property-', n, '/640/360')
FROM name_parts
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  country = VALUES(country),
  city = VALUES(city),
  district_name = VALUES(district_name),
  address1 = VALUES(address1),
  lat = VALUES(lat),
  lng = VALUES(lng),
  status = VALUES(status),
  rating = VALUES(rating),
  star_rating = VALUES(star_rating),
  location_rating = VALUES(location_rating),
  popularity_score = VALUES(popularity_score),
  property_type_code = VALUES(property_type_code),
  thumbnail_url = VALUES(thumbnail_url),
  updated_at = NOW(3);

-- Room types (60,000)
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

-- Taxonomy + relations for search facets
INSERT INTO brand(name)
VALUES
  ('Asteria'),
  ('Northpoint'),
  ('Harborline'),
  ('Evercrest'),
  ('Golden Laurel'),
  ('Bluewave'),
  ('Summit'),
  ('Lumin'),
  ('Oakridge'),
  ('Mayfield'),
  ('Solaria'),
  ('Riverton')
ON DUPLICATE KEY UPDATE
  name = VALUES(name);

DELETE FROM property_brand WHERE property_id BETWEEN 100001 AND 100000 + @property_count;
INSERT INTO property_brand(property_id, brand_id)
SELECT
  p.id,
  b.id
FROM property p
JOIN brand b
  ON b.name = CASE MOD(p.id, 12)
    WHEN 0 THEN 'Asteria'
    WHEN 1 THEN 'Northpoint'
    WHEN 2 THEN 'Harborline'
    WHEN 3 THEN 'Evercrest'
    WHEN 4 THEN 'Golden Laurel'
    WHEN 5 THEN 'Bluewave'
    WHEN 6 THEN 'Summit'
    WHEN 7 THEN 'Lumin'
    WHEN 8 THEN 'Oakridge'
    WHEN 9 THEN 'Mayfield'
    WHEN 10 THEN 'Solaria'
    ELSE 'Riverton'
  END
WHERE p.id BETWEEN 100001 AND 100000 + @property_count;

DELETE FROM property_theme WHERE property_id BETWEEN 100001 AND 100000 + @property_count;
INSERT INTO property_theme(property_id, theme_code)
SELECT
  p.id,
  CASE MOD(p.id, 5)
    WHEN 0 THEN 'family'
    WHEN 1 THEN 'business'
    WHEN 2 THEN 'romance'
    WHEN 3 THEN 'nature'
    ELSE 'shopping'
  END
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count;

DELETE FROM property_payment_option WHERE property_id BETWEEN 100001 AND 100000 + @property_count;
INSERT INTO property_payment_option(property_id, payment_option_code)
SELECT p.id, 'pay_now'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count;

INSERT INTO property_payment_option(property_id, payment_option_code)
SELECT p.id, 'free_cancel'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 3) IN (0, 2)
ON DUPLICATE KEY UPDATE payment_option_code = VALUES(payment_option_code);

INSERT INTO property_payment_option(property_id, payment_option_code)
SELECT p.id, 'pay_later'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) IN (0, 1)
ON DUPLICATE KEY UPDATE payment_option_code = VALUES(payment_option_code);

INSERT INTO property_payment_option(property_id, payment_option_code)
SELECT p.id, 'no_prepay'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 5) IN (0, 2)
ON DUPLICATE KEY UPDATE payment_option_code = VALUES(payment_option_code);

DELETE FROM property_amenity WHERE property_id BETWEEN 100001 AND 100000 + @property_count;
INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'wifi'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count;

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'breakfast'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 2) = 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'pool'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 3) = 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'gym'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) = 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'parking'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 3) <> 1
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'spa'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 5) = 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'ocean_view'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND p.city IN ('Busan', 'Jeju', 'Sydney', 'Melbourne', 'Bali')
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'kitchen'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) = 1
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

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

-- Curated district naming for KR cities (for server-driven facets/recommendations)
UPDATE property
SET district_name = ELT(MOD(id, 6) + 1, '강남', '명동', '홍대', '이태원', '잠실', '여의도')
WHERE city = 'Seoul';

UPDATE property
SET district_name = ELT(MOD(id, 6) + 1, '해운대', '광안리', '서면', '남포동', '송정', '센텀')
WHERE city = 'Busan';

UPDATE property
SET district_name = ELT(MOD(id, 6) + 1, '제주시', '애월', '서귀포', '중문', '성산', '함덕')
WHERE city = 'Jeju';

DELETE FROM district WHERE city IN ('Seoul', 'Busan', 'Jeju');
INSERT INTO district(city, name, blurb, rank_score)
VALUES
  ('Seoul', '강남', '트렌디한 쇼핑과 미식, 비즈니스 중심지', 98),
  ('Seoul', '명동', '도심 쇼핑과 접근성이 좋은 관광 중심지', 96),
  ('Seoul', '홍대', '젊은 감성의 문화/공연/카페 밀집 지역', 94),
  ('Seoul', '이태원', '다국적 레스토랑과 나이트 라이프', 92),
  ('Seoul', '잠실', '가족 여행과 대형 복합시설 접근 우수', 90),
  ('Seoul', '여의도', '한강 전망과 금융권 비즈니스 거점', 88),
  ('Busan', '해운대', '오션뷰 숙소와 해변 액티비티 중심', 99),
  ('Busan', '광안리', '야경과 해변 산책이 매력적인 해안 지역', 96),
  ('Busan', '서면', '교통이 편리한 쇼핑/맛집 중심지', 94),
  ('Busan', '남포동', '로컬 시장과 먹거리가 풍부한 구도심', 92),
  ('Busan', '송정', '서핑과 여유로운 해변 휴양 지역', 89),
  ('Busan', '센텀', '대형 몰/전시 접근성이 좋은 도심권', 88),
  ('Jeju', '제주시', '공항 접근이 좋은 도심 숙소 밀집권', 97),
  ('Jeju', '애월', '감성 카페와 해안 드라이브 코스', 94),
  ('Jeju', '서귀포', '자연 경관과 관광 명소 접근 우수', 93),
  ('Jeju', '중문', '리조트와 휴양형 숙소가 많은 지역', 92),
  ('Jeju', '성산', '일출 명소와 액티비티 접근이 좋은 동부권', 89),
  ('Jeju', '함덕', '에메랄드 바다와 가족 여행 친화 지역', 87);

DELETE FROM city_featured_property WHERE city IN ('Seoul', 'Busan', 'Jeju');
INSERT INTO city_featured_property(city, property_id, rank_score)
SELECT city, id, rank_score
FROM (
  SELECT
    p.city,
    p.id,
    (1000 - (ROW_NUMBER() OVER (
      PARTITION BY p.city
      ORDER BY p.rating DESC, p.popularity_score DESC, p.id ASC
    ) * 10)) AS rank_score,
    ROW_NUMBER() OVER (
      PARTITION BY p.city
      ORDER BY p.rating DESC, p.popularity_score DESC, p.id ASC
    ) AS rn
  FROM property p
  WHERE p.status = 'ACTIVE'
    AND p.city IN ('Seoul', 'Busan', 'Jeju')
) ranked
WHERE rn <= 16;

DELETE FROM city_poi_popular WHERE city IN ('Seoul', 'Busan', 'Jeju');
INSERT INTO city_poi_popular(city, poi_id, rank_score)
SELECT city, id, popularity_score
FROM (
  SELECT
    poi.city,
    poi.id,
    poi.popularity_score,
    ROW_NUMBER() OVER (
      PARTITION BY poi.city
      ORDER BY poi.popularity_score DESC, poi.rating_score DESC, poi.id ASC
    ) AS rn
  FROM poi
  WHERE active = 1
    AND poi.city IN ('Seoul', 'Busan', 'Jeju')
) ranked
WHERE rn <= 24;

DELETE FROM city_day_min_price
WHERE stay_date >= CURDATE()
  AND stay_date < DATE_ADD(CURDATE(), INTERVAL 120 DAY);

INSERT INTO city_day_min_price(city, stay_date, min_price_krw)
WITH RECURSIVE day_seq(day_offset) AS (
  SELECT 0
  UNION ALL
  SELECT day_offset + 1 FROM day_seq WHERE day_offset < 119
),
city_min_price AS (
  SELECT p.city, MIN(rt.base_price) AS min_price
  FROM property p
  JOIN room_type rt
    ON rt.property_id = p.id
   AND rt.status = 'ACTIVE'
  WHERE p.status = 'ACTIVE'
    AND p.city IS NOT NULL
  GROUP BY p.city
)
SELECT
  cmp.city,
  DATE_ADD(CURDATE(), INTERVAL day_seq.day_offset DAY),
  ROUND(
    cmp.min_price * (
      1 + CASE
        WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL day_seq.day_offset DAY)) IN (1, 7) THEN 0.10
        WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL day_seq.day_offset DAY)) = 6 THEN 0.07
        ELSE 0
      END
    )
  )
FROM city_min_price cmp
CROSS JOIN day_seq
ON DUPLICATE KEY UPDATE
  min_price_krw = VALUES(min_price_krw),
  updated_at = NOW(3);

-- Busan shopping POI boost for Korean shopping intent queries
INSERT INTO poi(
  id, name, category, city, lat, lng, address, description,
  popularity_score, rating_score, active
)
VALUES
  (690001, 'Busan 서면 쇼핑몰', 'shopping', 'Busan', 35.1585000, 129.0597000, 'Busan, Seomyeon district', '부산 쇼핑몰과 편집숍이 모인 쇼핑 중심지', 910, 4.7, 1),
  (690002, 'Busan 광복동 쇼핑거리', 'shopping', 'Busan', 35.0989000, 129.0305000, 'Busan, Gwangbok-dong', '부산 대표 쇼핑 거리와 라이프스타일 스토어', 880, 4.6, 1),
  (690003, 'Busan 해운대 마린 쇼핑몰', 'shopping', 'Busan', 35.1613000, 129.1640000, 'Busan, Haeundae', '해운대 인근 쇼핑몰과 브랜드 매장', 865, 4.6, 1),
  (690004, 'Busan 센텀 패션몰', 'shopping', 'Busan', 35.1696000, 129.1294000, 'Busan, Centum', '센텀시티 패션 쇼핑과 트렌드 편집숍', 905, 4.7, 1),
  (690005, 'Busan 남포동 로컬 쇼핑', 'shopping', 'Busan', 35.0979000, 129.0348000, 'Busan, Nampo-dong', '남포동 로컬 쇼핑 스팟과 소품샵', 835, 4.5, 1),
  (690006, 'Busan 전포 카페거리 편집샵', 'shopping', 'Busan', 35.1564000, 129.0665000, 'Busan, Jeonpo', '전포 감성 편집숍과 라이프스타일 상점', 820, 4.5, 1),
  (690007, 'Busan 부산역 쇼핑센터', 'shopping', 'Busan', 35.1154000, 129.0410000, 'Busan Station area', '부산역 근처 쇼핑센터와 기념품 상점', 790, 4.4, 1),
  (690008, 'Busan 광안리 라이프스타일몰', 'shopping', 'Busan', 35.1535000, 129.1186000, 'Busan, Gwangalli', '광안리 주변 라이프스타일 쇼핑몰', 810, 4.5, 1),
  (690009, 'Busan 사상 쇼핑타운', 'shopping', 'Busan', 35.1624000, 128.9845000, 'Busan, Sasang', '사상권 대형 쇼핑타운과 아울렛', 760, 4.4, 1),
  (690010, 'Busan 동래 쇼핑플라자', 'shopping', 'Busan', 35.2050000, 129.0787000, 'Busan, Dongnae', '동래권 쇼핑플라자와 생활 매장', 770, 4.4, 1),
  (690011, 'Busan 연산동 쇼핑스퀘어', 'shopping', 'Busan', 35.1866000, 129.0821000, 'Busan, Yeonsan', '연산동 쇼핑스퀘어와 팝업 스토어', 745, 4.3, 1),
  (690012, 'Busan 덕천 패밀리 쇼핑몰', 'shopping', 'Busan', 35.2103000, 129.0057000, 'Busan, Deokcheon', '가족 단위 방문이 많은 부산 쇼핑몰', 730, 4.3, 1)
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
