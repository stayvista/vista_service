-- Bulk local seed for load test and search
SET SESSION cte_max_recursion_depth = 40000;

INSERT INTO partner_account(id, name, type, status)
VALUES (1, 'Demo Hotel Partner', 'HOTEL', 'ACTIVE')
ON DUPLICATE KEY UPDATE name=name;

-- 10,000 properties across KR/JP/CN/US/EU/SEA
WITH RECURSIVE seq AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 10000
),
property_seed AS (
  SELECT
    seq.n AS n,
    MOD(seq.n - 1, 26) AS city_idx,
    CASE MOD(seq.n - 1, 26)
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
    CASE MOD(seq.n - 1, 26)
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
    CASE MOD(seq.n - 1, 26)
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
    CASE MOD(seq.n - 1, 26)
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
  FROM seq
)
INSERT INTO property(partner_id, name, country, city, address1, lat, lng, status, rating, thumbnail_url)
SELECT
  1,
  CONCAT('Demo Global Property ', LPAD(n, 5, '0'), ' ', city_name),
  country_code,
  city_name,
  CONCAT(city_name, ' District ', LPAD(MOD(n * 13, 240) + 1, 3, '0')),
  ROUND(base_lat + ((MOD(n, 41) - 20) / 2500), 7),
  ROUND(base_lng + ((MOD(n * 7, 41) - 20) / 2500), 7),
  'ACTIVE',
  3.5 + (MOD(n, 15) * 0.1),
  NULL
FROM property_seed
WHERE NOT EXISTS (
  SELECT 1
  FROM property p
  WHERE p.name = CONCAT('Demo Global Property ', LPAD(property_seed.n, 5, '0'), ' ', property_seed.city_name)
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
WHERE (p.name LIKE 'Demo Property %' OR p.name LIKE 'Demo Global Property %')
  AND rt.id IS NULL;

-- 6,000 POIs across global cities
WITH RECURSIVE poi_seq AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM poi_seq WHERE n < 6000
),
poi_city AS (
  SELECT
    poi_seq.n AS n,
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
INSERT INTO poi(
  id, name, category, city, lat, lng, address, description,
  popularity_score, rating_score, active
)
SELECT
  800000 + n,
  CONCAT(
    city_name,
    ' ',
    ELT(
      MOD(n * 5 + FLOOR(n / 7), 12) + 1,
      'Skyline', 'Riverfront', 'Central', 'Heritage', 'Harbor', 'Old Quarter',
      'Garden', 'Cultural', 'Market', 'Arts', 'Canal', 'Discovery'
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
  CONCAT(city_name, ', ', country_name, ' City Center'),
  CONCAT('Bulk seed POI #', n, ' in ', city_name),
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
