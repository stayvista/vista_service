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
  rating, star_rating, location_rating, popularity_score, property_type_code, thumbnail_url,
  review_count, beach_distance_m, is_beachfront, kid_free_stay
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
  CASE MOD(n, 18)
    WHEN 0 THEN 'hotel'
    WHEN 1 THEN 'resort'
    WHEN 2 THEN 'guesthouse'
    WHEN 3 THEN 'motel'
    WHEN 4 THEN 'hostel'
    WHEN 5 THEN 'apartment'
    WHEN 6 THEN 'serviced_apartment'
    WHEN 7 THEN 'homestay'
    WHEN 8 THEN 'inn'
    WHEN 9 THEN 'resort_villa'
    WHEN 10 THEN 'pension'
    WHEN 11 THEN 'private_house'
    WHEN 12 THEN 'capsule_hotel'
    WHEN 13 THEN 'holiday_park'
    WHEN 14 THEN 'villa'
    WHEN 15 THEN 'lodge'
    WHEN 16 THEN 'bungalow'
    ELSE 'boutique'
  END,
  CONCAT('https://picsum.photos/seed/stayvista-property-', n, '/640/360'),
  500 + MOD(n * 37, 25000),
  CASE
    WHEN city_name IN ('Busan', 'Jeju', 'Bali', 'Sydney', 'Melbourne', 'Barcelona', 'Athens', 'Manila', 'Auckland')
      THEN 80 + MOD(n * 19, 4800)
    ELSE 12000 + MOD(n * 11, 60000)
  END,
  CASE
    WHEN city_name IN ('Busan', 'Jeju', 'Bali', 'Sydney', 'Melbourne', 'Barcelona', 'Athens', 'Manila', 'Auckland')
      AND MOD(n, 7) = 0 THEN 1
    ELSE 0
  END,
  CASE WHEN MOD(n, 6) = 0 THEN 1 ELSE 0 END
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
  review_count = VALUES(review_count),
  beach_distance_m = VALUES(beach_distance_m),
  is_beachfront = VALUES(is_beachfront),
  kid_free_stay = VALUES(kid_free_stay),
  updated_at = NOW(3);

-- Room types (60,000)
INSERT INTO room_type(
  id, property_id, name, capacity_adults, capacity_children, bed_type, view_type, refundable, status, base_price, bedrooms
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
  90000 + (MOD(n * room_no, 8) * 15000),
  CASE
    WHEN room_no = 3 THEN 2
    WHEN MOD(n, 9) = 0 THEN 3
    ELSE 1
  END
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
  bedrooms = VALUES(bedrooms),
  updated_at = NOW(3);

-- Property reviews (DB-backed detail/review UI)
CREATE TABLE IF NOT EXISTS property_review (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id BIGINT NOT NULL,
  reviewer_name VARCHAR(120) NOT NULL,
  reviewer_country VARCHAR(2) NULL,
  traveler_type VARCHAR(80) NOT NULL DEFAULT '나홀로 여행객',
  stay_date DATE NOT NULL,
  nights INT NOT NULL DEFAULT 1,
  score_overall DECIMAL(3,1) NOT NULL,
  score_service DECIMAL(3,1) NULL,
  score_cleanliness DECIMAL(3,1) NULL,
  score_facility DECIMAL(3,1) NULL,
  score_value DECIMAL(3,1) NULL,
  score_location DECIMAL(3,1) NULL,
  title VARCHAR(255) NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_property_review_lookup (property_id, status, stay_date DESC, id DESC),
  KEY idx_property_review_score (property_id, score_overall),
  CONSTRAINT fk_property_review_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_review_tag (
  review_id BIGINT NOT NULL,
  tag VARCHAR(60) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (review_id, tag),
  KEY idx_property_review_tag_tag (tag, review_id),
  CONSTRAINT fk_property_review_tag_review FOREIGN KEY (review_id) REFERENCES property_review(id) ON DELETE CASCADE
) ENGINE=InnoDB;

SET @reviews_per_property := 3;

DELETE prt
FROM property_review_tag prt
JOIN property_review pr ON pr.id = prt.review_id
WHERE pr.property_id BETWEEN 100001 AND 100000 + @property_count;

DELETE FROM property_review
WHERE property_id BETWEEN 100001 AND 100000 + @property_count;

INSERT INTO property_review(
  property_id,
  reviewer_name,
  reviewer_country,
  traveler_type,
  stay_date,
  nights,
  score_overall,
  score_service,
  score_cleanliness,
  score_facility,
  score_value,
  score_location,
  title,
  body,
  status
)
WITH RECURSIVE property_seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM property_seq WHERE n < @property_count
),
review_seq(seq_no) AS (
  SELECT 1
  UNION ALL
  SELECT seq_no + 1 FROM review_seq WHERE seq_no < @reviews_per_property
)
SELECT
  p.id,
  ELT(
    MOD(property_seq.n * 7 + review_seq.seq_no * 13, 12) + 1,
    '민지', '서연', '지수', '하은', '유나', '도윤',
    '현우', '준서', '태현', '지안', '유진', '시우'
  ) AS reviewer_name,
  ELT(MOD(property_seq.n + review_seq.seq_no, 6) + 1, 'KR', 'JP', 'US', 'TW', 'SG', 'AU') AS reviewer_country,
  CASE MOD(property_seq.n + review_seq.seq_no, 4)
    WHEN 0 THEN '가족 여행객'
    WHEN 1 THEN '커플/2인 여행객'
    WHEN 2 THEN '나홀로 여행객'
    ELSE '비즈니스 여행객'
  END AS traveler_type,
  DATE_SUB(CURDATE(), INTERVAL (MOD(property_seq.n * 17 + review_seq.seq_no * 19, 330) + 1) DAY) AS stay_date,
  1 + MOD(property_seq.n + review_seq.seq_no, 4) AS nights,
  ROUND(7.8 + (MOD(property_seq.n * 11 + review_seq.seq_no * 5, 21) / 10), 1) AS score_overall,
  ROUND(LEAST(9.9, GREATEST(6.0, 7.9 + (MOD(property_seq.n * 13 + review_seq.seq_no * 7, 21) / 10))), 1) AS score_service,
  ROUND(LEAST(9.9, GREATEST(6.0, 7.7 + (MOD(property_seq.n * 9 + review_seq.seq_no * 11, 22) / 10))), 1) AS score_cleanliness,
  ROUND(LEAST(9.9, GREATEST(6.0, 7.6 + (MOD(property_seq.n * 5 + review_seq.seq_no * 17, 24) / 10))), 1) AS score_facility,
  ROUND(LEAST(9.9, GREATEST(6.0, 7.5 + (MOD(property_seq.n * 3 + review_seq.seq_no * 13, 23) / 10))), 1) AS score_value,
  ROUND(LEAST(9.9, GREATEST(6.0, 7.8 + (MOD(property_seq.n * 7 + review_seq.seq_no * 9, 21) / 10))), 1) AS score_location,
  CASE MOD(review_seq.seq_no, 3)
    WHEN 1 THEN '위치와 이동 동선이 편리했어요'
    WHEN 2 THEN '객실 컨디션과 청결이 만족스러웠어요'
    ELSE '서비스 응대가 빠르고 친절했어요'
  END AS title,
  CONCAT(
    p.city,
    ' 여행 기준으로 ',
    p.name,
    ' 투숙 경험이 안정적이었습니다. ',
    CASE MOD(review_seq.seq_no, 3)
      WHEN 1 THEN '체크인/체크아웃 동선이 편했고 주변 접근성이 좋았습니다.'
      WHEN 2 THEN '객실 청결 상태가 좋고 편의시설 사용이 수월했습니다.'
      ELSE '직원 응대가 빠르고 전반적인 숙박 경험이 만족스러웠습니다.'
    END
  ) AS body,
  'PUBLISHED' AS status
FROM property_seq
JOIN property p
  ON p.id = 100000 + property_seq.n
JOIN review_seq
WHERE p.status = 'ACTIVE';

INSERT INTO property_review_tag(review_id, tag)
SELECT
  pr.id,
  CASE MOD(pr.id, 6)
    WHEN 0 THEN '서비스'
    WHEN 1 THEN '조식'
    WHEN 2 THEN '위치'
    WHEN 3 THEN '청결'
    WHEN 4 THEN '객실 전망/뷰'
    ELSE '가족 여행객'
  END
FROM property_review pr
WHERE pr.property_id BETWEEN 100001 AND 100000 + @property_count
ON DUPLICATE KEY UPDATE tag = VALUES(tag);

INSERT INTO property_review_tag(review_id, tag)
SELECT
  pr.id,
  CASE MOD(pr.id + 3, 6)
    WHEN 0 THEN '서비스'
    WHEN 1 THEN '조식'
    WHEN 2 THEN '위치'
    WHEN 3 THEN '청결'
    WHEN 4 THEN '객실 전망/뷰'
    ELSE '가족 여행객'
  END
FROM property_review pr
WHERE pr.property_id BETWEEN 100001 AND 100000 + @property_count
ON DUPLICATE KEY UPDATE tag = VALUES(tag);

UPDATE property p
LEFT JOIN (
  SELECT property_id, COUNT(*) AS cnt
  FROM property_review
  WHERE status = 'PUBLISHED'
    AND property_id BETWEEN 100001 AND 100000 + @property_count
  GROUP BY property_id
) prc ON prc.property_id = p.id
SET p.review_count = COALESCE(prc.cnt, 0)
WHERE p.id BETWEEN 100001 AND 100000 + @property_count;

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

INSERT INTO property_theme(property_id, theme_code)
SELECT p.id, 'group'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) = 0
ON DUPLICATE KEY UPDATE theme_code = VALUES(theme_code);

INSERT INTO property_theme(property_id, theme_code)
SELECT p.id, 'workation'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 5) = 0
ON DUPLICATE KEY UPDATE theme_code = VALUES(theme_code);

INSERT INTO property_theme(property_id, theme_code)
SELECT p.id, 'pet'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 9) = 0
ON DUPLICATE KEY UPDATE theme_code = VALUES(theme_code);

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
SELECT p.id, 'pay_at_property'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) IN (0, 1)
ON DUPLICATE KEY UPDATE payment_option_code = VALUES(payment_option_code);

INSERT INTO property_payment_option(property_id, payment_option_code)
SELECT p.id, 'reserve_now_pay_later'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 5) IN (0, 2)
ON DUPLICATE KEY UPDATE payment_option_code = VALUES(payment_option_code);

INSERT INTO property_payment_option(property_id, payment_option_code)
SELECT p.id, 'no_credit_card'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 11) = 0
ON DUPLICATE KEY UPDATE payment_option_code = VALUES(payment_option_code);

DELETE FROM property_amenity WHERE property_id BETWEEN 100001 AND 100000 + @property_count;
INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'internet'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count;

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'wifi'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

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
SELECT p.id, 'frontdesk_24h'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 3) <> 2
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'family_friendly'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) IN (0, 1)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'non_smoking'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 5) <> 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'restaurant'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 2) = 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'smoking_area'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 6) IN (0, 1)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'pet_friendly'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 9) = 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'accessible'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 8) IN (0, 1)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'nightclub'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND p.city IN ('Seoul', 'Busan', 'Tokyo', 'Bangkok')
  AND MOD(p.id, 11) < 2
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'golf_course'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND p.city IN ('Jeju', 'Seoul', 'Sydney', 'Melbourne')
  AND MOD(p.id, 13) IN (0, 1)
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

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'fridge'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 3) <> 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'air_conditioning'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) <> 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'tv'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 5) <> 1
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'heating'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'washer'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) = 1
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'coffee_maker'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 3) = 2
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'bathtub'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 5) IN (0, 1, 2)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'toiletries'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 2) = 1
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'balcony'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND p.city IN ('Seoul', 'Busan', 'Jeju', 'Barcelona', 'Athens', 'Sydney', 'Melbourne')
  AND MOD(p.id, 3) = 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'private_pool'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND p.city IN ('Jeju', 'Bali', 'Phuket', 'Dubai')
  AND MOD(p.id, 17) = 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'food_delivery_external'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 3) IN (0, 1)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'family_delivery_allowed'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 7) IN (0, 1, 2)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'early_checkin'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 6) IN (0, 1, 2)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'espresso_machine'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 8) IN (0, 1)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'late_checkout'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 5) IN (0, 1)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'convenience_delivery'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 5) IN (1, 2, 3)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'free_snack'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) = 0
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'airport_transfer'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 4) IN (0, 2)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'treadmill'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 9) IN (0, 1, 2)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'dinner_included'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 6) IN (0, 1)
ON DUPLICATE KEY UPDATE amenity_code = VALUES(amenity_code);

INSERT INTO property_amenity(property_id, amenity_code)
SELECT p.id, 'afternoon_tea'
FROM property p
WHERE p.id BETWEEN 100001 AND 100000 + @property_count
  AND MOD(p.id, 10) IN (0, 1, 2)
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

INSERT IGNORE INTO ticket_event(id, product_id, start_time, end_time, status)
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

-- -----------------------------------------------------------------------------
-- DB-backed rich content expansion (home/search/property detail)
-- -----------------------------------------------------------------------------

-- Ensure optional columns exist for media urls.
SELECT COUNT(*) INTO @has_product_image_url
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'product'
  AND column_name = 'image_url';
SET @add_product_image_url_sql = IF(
  @has_product_image_url = 0,
  'ALTER TABLE product ADD COLUMN image_url VARCHAR(500) NULL',
  'DO 0'
);
PREPARE add_product_image_url_stmt FROM @add_product_image_url_sql;
EXECUTE add_product_image_url_stmt;
DEALLOCATE PREPARE add_product_image_url_stmt;

SELECT COUNT(*) INTO @has_package_image_url
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'package_product'
  AND column_name = 'image_url';
SET @add_package_image_url_sql = IF(
  @has_package_image_url = 0,
  'ALTER TABLE package_product ADD COLUMN image_url VARCHAR(500) NULL',
  'DO 0'
);
PREPARE add_package_image_url_stmt FROM @add_package_image_url_sql;
EXECUTE add_package_image_url_stmt;
DEALLOCATE PREPARE add_package_image_url_stmt;

SELECT COUNT(*) INTO @has_poi_image_urls
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'poi'
  AND column_name = 'image_urls';
SET @add_poi_image_urls_sql = IF(
  @has_poi_image_urls = 0,
  'ALTER TABLE poi ADD COLUMN image_urls TEXT NULL',
  'DO 0'
);
PREPARE add_poi_image_urls_stmt FROM @add_poi_image_urls_sql;
EXECUTE add_poi_image_urls_stmt;
DEALLOCATE PREPARE add_poi_image_urls_stmt;

CREATE TABLE IF NOT EXISTS promotion_campaign (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  code                VARCHAR(64) NOT NULL,
  section             VARCHAR(40) NOT NULL,
  title               VARCHAR(160) NOT NULL,
  subtitle            VARCHAR(220) NULL,
  description         VARCHAR(600) NULL,
  city                VARCHAR(100) NULL,
  image_url           VARCHAR(500) NULL,
  badge_text          VARCHAR(40) NULL,
  discount_text       VARCHAR(80) NULL,
  currency            VARCHAR(10) NOT NULL DEFAULT 'KRW',
  coupon_value_type   VARCHAR(16) NOT NULL DEFAULT 'PERCENT',
  coupon_value        DECIMAL(10,2) NOT NULL DEFAULT 0,
  min_order_amount    BIGINT NOT NULL DEFAULT 0,
  issue_limit         INT NOT NULL,
  issued_count        INT NOT NULL DEFAULT 0,
  starts_at           DATETIME(3) NOT NULL,
  ends_at             DATETIME(3) NOT NULL,
  priority            INT NOT NULL DEFAULT 0,
  status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_promotion_campaign_code (code),
  KEY idx_promotion_campaign_section (section, status, starts_at, ends_at),
  KEY idx_promotion_campaign_city (city, status, starts_at, ends_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS home_hero (
  id                   TINYINT PRIMARY KEY,
  eyebrow_text         VARCHAR(120) NOT NULL,
  title_text           VARCHAR(180) NOT NULL,
  summary_text         VARCHAR(400) NOT NULL,
  background_image_url VARCHAR(500) NULL,
  active               TINYINT(1) NOT NULL DEFAULT 1,
  created_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS home_hero_metric (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  hero_id       TINYINT NOT NULL,
  metric_value  VARCHAR(60) NOT NULL,
  metric_label  VARCHAR(120) NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_home_hero_metric (hero_id, active, display_order),
  CONSTRAINT fk_home_hero_metric_hero FOREIGN KEY (hero_id) REFERENCES home_hero(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS home_quick_filter (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  label         VARCHAR(80) NOT NULL,
  filter_key    VARCHAR(40) NOT NULL,
  filter_value  VARCHAR(80) NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_home_quick_filter (active, display_order)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS home_destination_card (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  section_code  VARCHAR(20) NOT NULL,
  city          VARCHAR(120) NOT NULL,
  country       VARCHAR(10) NULL,
  label         VARCHAR(120) NOT NULL,
  image_url     VARCHAR(500) NULL,
  highlights    VARCHAR(220) NULL,
  property_count INT NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_home_destination_section_city (section_code, city),
  KEY idx_home_destination_section (section_code, active, display_order)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS promotion_section (
  section_code  VARCHAR(40) PRIMARY KEY,
  title         VARCHAR(120) NOT NULL,
  subtitle      VARCHAR(220) NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_editorial (
  property_id               BIGINT PRIMARY KEY,
  short_description         TEXT NULL,
  long_description          TEXT NULL,
  check_in_time             VARCHAR(10) NULL,
  check_out_time            VARCHAR(10) NULL,
  airport_transfer_fee_krw  BIGINT NULL,
  breakfast_fee_krw         BIGINT NULL,
  remodeled_year            INT NULL,
  children_policy           VARCHAR(220) NULL,
  created_at                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_property_editorial_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_highlight (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id   BIGINT NOT NULL,
  content       VARCHAR(255) NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_property_highlight (property_id, active, display_order),
  CONSTRAINT fk_property_highlight_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_gallery_image (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id   BIGINT NOT NULL,
  image_url     VARCHAR(500) NOT NULL,
  is_cover      TINYINT(1) NOT NULL DEFAULT 0,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_property_gallery (property_id, active, is_cover, display_order),
  CONSTRAINT fk_property_gallery_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_staycation_card (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  property_id   BIGINT NOT NULL,
  card_code     VARCHAR(40) NOT NULL,
  title         VARCHAR(100) NOT NULL,
  subtitle      VARCHAR(200) NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_property_staycation_card (property_id, card_code),
  KEY idx_property_staycation_card (property_id, active, display_order),
  CONSTRAINT fk_property_staycation_card_property FOREIGN KEY (property_id) REFERENCES property(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS property_staycation_item (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  card_id       BIGINT NOT NULL,
  item_text     VARCHAR(160) NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_property_staycation_item (card_id, active, display_order),
  CONSTRAINT fk_property_staycation_item_card FOREIGN KEY (card_id) REFERENCES property_staycation_card(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS room_type_media (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_type_id  BIGINT NOT NULL,
  image_url     VARCHAR(500) NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_room_type_media (room_type_id, active, display_order),
  CONSTRAINT fk_room_type_media_room_type FOREIGN KEY (room_type_id) REFERENCES room_type(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS room_type_feature (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_type_id  BIGINT NOT NULL,
  feature_text  VARCHAR(160) NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_room_type_feature (room_type_id, active, display_order),
  CONSTRAINT fk_room_type_feature_room_type FOREIGN KEY (room_type_id) REFERENCES room_type(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS room_rate_plan (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_type_id   BIGINT NOT NULL,
  plan_code      VARCHAR(60) NOT NULL,
  occupancy_text VARCHAR(120) NULL,
  pay_summary    VARCHAR(120) NULL,
  urgency_text   VARCHAR(120) NULL,
  list_price_krw BIGINT NOT NULL,
  sale_price_krw BIGINT NOT NULL,
  display_order  INT NOT NULL DEFAULT 0,
  active         TINYINT(1) NOT NULL DEFAULT 1,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_room_rate_plan_code (room_type_id, plan_code),
  KEY idx_room_rate_plan (room_type_id, active, display_order),
  CONSTRAINT fk_room_rate_plan_room_type FOREIGN KEY (room_type_id) REFERENCES room_type(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS room_rate_plan_benefit (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id       BIGINT NOT NULL,
  benefit_text  VARCHAR(160) NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_room_rate_plan_benefit (plan_id, active, display_order),
  CONSTRAINT fk_room_rate_plan_benefit_plan FOREIGN KEY (plan_id) REFERENCES room_rate_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB;

INSERT INTO home_hero(id, eyebrow_text, title_text, summary_text, background_image_url, active)
VALUES (
  1,
  'VERIFIED INVENTORY · REAL-TIME BOOKING',
  '잊지못할 여행을 선물하세요',
  '숙소, 티켓, 패키지 재고를 하나의 화면에서 실시간으로 확인하고 예약 하실수 있습니다.',
  'https://picsum.photos/seed/stayvista-hero-main/1920/1080',
  1
)
ON DUPLICATE KEY UPDATE
  eyebrow_text = VALUES(eyebrow_text),
  title_text = VALUES(title_text),
  summary_text = VALUES(summary_text),
  background_image_url = VALUES(background_image_url),
  active = VALUES(active),
  updated_at = NOW(3);

DELETE FROM home_hero_metric WHERE hero_id = 1;
INSERT INTO home_hero_metric(hero_id, metric_value, metric_label, display_order, active)
VALUES
  (1, '1.2M+', '누적 예약 건수', 10, 1),
  (1, '4.8 / 5', '실제 투숙 후기 평점', 20, 1),
  (1, '24/7', '운영 지원 및 고객 응대', 30, 1);

DELETE FROM home_quick_filter;
INSERT INTO home_quick_filter(label, filter_key, filter_value, display_order, active)
VALUES
  ('오션뷰', 'amenities', 'ocean_view', 10, 1),
  ('프라이빗 풀', 'amenities', 'private_pool', 20, 1),
  ('조식 포함', 'amenities', 'breakfast', 30, 1),
  ('무료 취소', 'payment_options', 'free_cancel', 40, 1),
  ('24시간 체크인', 'amenities', 'front24h', 50, 1),
  ('반려동물 가능', 'amenities', 'pet_friendly', 60, 1),
  ('공항 이동', 'amenities', 'airport_transfer', 70, 1),
  ('가족 여행', 'themes', 'family', 80, 1),
  ('도심 2km 이내', 'distance_bands', 'under_2km', 90, 1),
  ('4성급 이상', 'stars', '4,5', 100, 1);

DELETE FROM home_destination_card;
INSERT INTO home_destination_card(section_code, city, country, label, image_url, highlights, property_count, display_order, active)
VALUES
  ('DOMESTIC', 'Seoul', 'KR', '서울', 'https://picsum.photos/seed/stayvista-city-seoul/640/420', '쇼핑, 레스토랑, 야경', 5945, 10, 1),
  ('DOMESTIC', 'Busan', 'KR', '부산', 'https://picsum.photos/seed/stayvista-city-busan/640/420', '해변, 해산물, 오션뷰', 2734, 20, 1),
  ('DOMESTIC', 'Jeju', 'KR', '제주', 'https://picsum.photos/seed/stayvista-city-jeju/640/420', '자연경관, 드라이브, 휴양', 4939, 30, 1),
  ('DOMESTIC', 'Incheon', 'KR', '인천', 'https://picsum.photos/seed/stayvista-city-incheon/640/420', '공항 접근, 바다 전망', 2147, 40, 1),
  ('DOMESTIC', 'Sokcho', 'KR', '속초', 'https://picsum.photos/seed/stayvista-city-sokcho/640/420', '바다, 시장, 설악산', 800, 50, 1),
  ('DOMESTIC', 'Gangneung', 'KR', '강릉', 'https://picsum.photos/seed/stayvista-city-gangneung/640/420', '카페거리, 해변, 서핑', 1290, 60, 1),
  ('DOMESTIC', 'Gyeongju', 'KR', '경주', 'https://picsum.photos/seed/stayvista-city-gyeongju/640/420', '역사유적, 가족여행', 910, 70, 1),
  ('DOMESTIC', 'Yeosu', 'KR', '여수', 'https://picsum.photos/seed/stayvista-city-yeosu/640/420', '야경, 해상케이블카', 760, 80, 1),
  ('GLOBAL', 'Tokyo', 'JP', '도쿄', 'https://picsum.photos/seed/stayvista-city-tokyo/640/420', '도심 쇼핑, 미식, 문화', 12486, 10, 1),
  ('GLOBAL', 'Osaka', 'JP', '오사카', 'https://picsum.photos/seed/stayvista-city-osaka/640/420', '먹거리, 관광, 쇼핑', 8260, 20, 1),
  ('GLOBAL', 'Bangkok', 'TH', '방콕', 'https://picsum.photos/seed/stayvista-city-bangkok/640/420', '가성비 호텔, 야시장', 12048, 30, 1),
  ('GLOBAL', 'Singapore', 'SG', '싱가포르', 'https://picsum.photos/seed/stayvista-city-singapore/640/420', '비즈니스, 도심 휴양', 6450, 40, 1),
  ('GLOBAL', 'Paris', 'FR', '파리', 'https://picsum.photos/seed/stayvista-city-paris/640/420', '예술, 미식, 쇼핑', 11230, 50, 1),
  ('GLOBAL', 'London', 'GB', '런던', 'https://picsum.photos/seed/stayvista-city-london/640/420', '랜드마크, 뮤지컬', 10920, 60, 1),
  ('GLOBAL', 'Barcelona', 'ES', '바르셀로나', 'https://picsum.photos/seed/stayvista-city-barcelona/640/420', '해변, 건축, 야경', 8430, 70, 1),
  ('GLOBAL', 'New York', 'US', '뉴욕', 'https://picsum.photos/seed/stayvista-city-nyc/640/420', '브로드웨이, 쇼핑', 13320, 80, 1),
  ('GLOBAL', 'Sydney', 'AU', '시드니', 'https://picsum.photos/seed/stayvista-city-sydney/640/420', '하버뷰, 비치', 5980, 90, 1),
  ('GLOBAL', 'Dubai', 'AE', '두바이', 'https://picsum.photos/seed/stayvista-city-dubai/640/420', '럭셔리, 사막 투어', 7740, 100, 1);

INSERT INTO promotion_section(section_code, title, subtitle, display_order, active)
VALUES
  ('HOTEL_SALE', '숙소 세일', '한정 수량 쿠폰 발급', 10, 1),
  ('ACTIVITY_PROMO', '즐길 거리 프로모션', '티켓/체험 얼리버드 특가', 20, 1),
  ('RECOMMENDED_STAY', '추천 숙소', '브랜드 제휴 할인 카드', 30, 1),
  ('GLOBAL_PICK', '해외 인기 딜', '해외 도시 한정 프로모션', 40, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  subtitle = VALUES(subtitle),
  display_order = VALUES(display_order),
  active = VALUES(active),
  updated_at = NOW(3);

SET @promo_campaign_count := 800;
DELETE FROM promotion_campaign WHERE code LIKE 'SEED26_%';
INSERT INTO promotion_campaign(
  code, section, title, subtitle, description, city, image_url, badge_text, discount_text,
  currency, coupon_value_type, coupon_value, min_order_amount, issue_limit, issued_count,
  starts_at, ends_at, priority, status
)
WITH RECURSIVE campaign_seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM campaign_seq WHERE n < @promo_campaign_count
)
SELECT
  CONCAT('SEED26_', LPAD(n, 4, '0')),
  CASE MOD(n - 1, 4)
    WHEN 0 THEN 'HOTEL_SALE'
    WHEN 1 THEN 'ACTIVITY_PROMO'
    WHEN 2 THEN 'RECOMMENDED_STAY'
    ELSE 'GLOBAL_PICK'
  END,
  CONCAT(
    ELT(MOD(n * 7, 10) + 1, 'MEGA SALE', 'SPRING DEAL', 'EARLY BIRD', 'CITY BREAK', 'WEEKEND PICK', 'FLASH SALE', 'LIMITED', 'FAMILY PACK', 'COUPON DAY', 'SMART BOOK'),
    ' · ',
    ELT(MOD(n - 1, 12) + 1, '서울', '부산', '제주', '도쿄', '오사카', '방콕', '싱가포르', '파리', '런던', '뉴욕', '시드니', '두바이')
  ),
  CONCAT('기간 한정 ', ELT(MOD(n * 5, 6) + 1, '숙소', '티켓', '패키지', '브랜드', '도시', '주말'), ' 혜택'),
  CONCAT('선착순 발급 · 최소 주문금액 조건 충족 시 자동 적용 (캠페인 ', n, ').'),
  ELT(MOD(n - 1, 12) + 1, 'Seoul', 'Busan', 'Jeju', 'Tokyo', 'Osaka', 'Bangkok', 'Singapore', 'Paris', 'London', 'New York', 'Sydney', 'Dubai'),
  CONCAT('https://picsum.photos/seed/stayvista-promo-rich-', n, '/960/520'),
  ELT(MOD(n, 6) + 1, 'HOT', 'FLASH', 'LIMITED', 'CITY', 'MEGA', 'PICK'),
  CASE
    WHEN MOD(n, 5) = 0 THEN CONCAT('최대 ', 15000 + MOD(n * 571, 85000), '원 할인')
    ELSE CONCAT('최대 ', 5 + MOD(n * 11, 26), '% 할인')
  END,
  CASE
    WHEN MOD(n, 12) IN (1, 2, 3) THEN 'JPY'
    WHEN MOD(n, 12) IN (4, 5, 6) THEN 'USD'
    WHEN MOD(n, 12) IN (7, 8) THEN 'EUR'
    ELSE 'KRW'
  END,
  CASE WHEN MOD(n, 5) = 0 THEN 'AMOUNT' ELSE 'PERCENT' END,
  CASE
    WHEN MOD(n, 5) = 0 THEN 8000 + MOD(n * 37, 92000)
    ELSE 4 + MOD(n * 7, 23)
  END,
  30000 + MOD(n * 4103, 240000),
  500 + MOD(n * 43, 4500),
  MOD(n * 19, 180),
  DATE_SUB(NOW(3), INTERVAL MOD(n, 9) DAY),
  DATE_ADD(NOW(3), INTERVAL 7 + MOD(n * 3, 44) DAY),
  50 + MOD(n * 13, 120),
  CASE WHEN MOD(n, 17) = 0 THEN 'PAUSED' ELSE 'ACTIVE' END
FROM campaign_seq;

SET @ticket_product_count := 800;
SET @ticket_event_days := 60;
SET @room_type_total := @property_count * @room_per_property;

INSERT INTO product(id, partner_id, product_type, name, city, lat, lng, image_url, status)
WITH RECURSIVE ticket_seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM ticket_seq WHERE n < @ticket_product_count
)
SELECT
  300000 + n,
  900002,
  CASE MOD(n, 3)
    WHEN 0 THEN 'ACTIVITY'
    WHEN 1 THEN 'TICKET'
    ELSE 'PASS'
  END,
  CONCAT(
    ELT(MOD(n - 1, 12) + 1, 'Seoul', 'Busan', 'Jeju', 'Tokyo', 'Osaka', 'Bangkok', 'Singapore', 'Paris', 'London', 'New York', 'Sydney', 'Dubai'),
    ' ',
    ELT(MOD(n * 7, 10) + 1, 'Weekend Explorer', 'Culture Pass', 'Night Tour', 'Family Fun', 'Food Walk', 'Museum Hopper', 'City Highlights', 'River Cruise', 'Coastal Route', 'Festival Ticket')
  ),
  ELT(MOD(n - 1, 12) + 1, 'Seoul', 'Busan', 'Jeju', 'Tokyo', 'Osaka', 'Bangkok', 'Singapore', 'Paris', 'London', 'New York', 'Sydney', 'Dubai'),
  CASE MOD(n - 1, 12)
    WHEN 0 THEN 37.5665
    WHEN 1 THEN 35.1796
    WHEN 2 THEN 33.4996
    WHEN 3 THEN 35.6762
    WHEN 4 THEN 34.6937
    WHEN 5 THEN 13.7563
    WHEN 6 THEN 1.3521
    WHEN 7 THEN 48.8566
    WHEN 8 THEN 51.5074
    WHEN 9 THEN 40.7128
    WHEN 10 THEN -33.8688
    ELSE 25.2048
  END + ((MOD(n, 17) - 8) / 900),
  CASE MOD(n - 1, 12)
    WHEN 0 THEN 126.9780
    WHEN 1 THEN 129.0756
    WHEN 2 THEN 126.5312
    WHEN 3 THEN 139.6503
    WHEN 4 THEN 135.5023
    WHEN 5 THEN 100.5018
    WHEN 6 THEN 103.8198
    WHEN 7 THEN 2.3522
    WHEN 8 THEN -0.1278
    WHEN 9 THEN -74.0060
    WHEN 10 THEN 151.2093
    ELSE 55.2708
  END + ((MOD(n * 11, 17) - 8) / 900),
  CONCAT('https://picsum.photos/seed/stayvista-ticket-rich-', n, '/640/380'),
  'ACTIVE'
FROM ticket_seq
ON DUPLICATE KEY UPDATE
  partner_id = VALUES(partner_id),
  product_type = VALUES(product_type),
  name = VALUES(name),
  city = VALUES(city),
  lat = VALUES(lat),
  lng = VALUES(lng),
  image_url = VALUES(image_url),
  status = VALUES(status),
  updated_at = NOW(3);

INSERT IGNORE INTO ticket_event(id, product_id, start_time, end_time, status)
WITH RECURSIVE day_seq(day_offset) AS (
  SELECT 0
  UNION ALL
  SELECT day_offset + 1 FROM day_seq WHERE day_offset + 1 < @ticket_event_days
),
slot_seq(slot_no) AS (
  SELECT 1
  UNION ALL
  SELECT 2
  UNION ALL
  SELECT 3
),
product_scope AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn
  FROM product
  WHERE id BETWEEN 300001 AND (300000 + @ticket_product_count)
)
SELECT
  400000000 + (product_scope.rn * 1000) + (day_seq.day_offset * 10) + slot_seq.slot_no,
  product_scope.id,
  TIMESTAMP(
    DATE_ADD(CURDATE(), INTERVAL day_seq.day_offset DAY),
    CASE slot_seq.slot_no
      WHEN 1 THEN '09:00:00'
      WHEN 2 THEN '13:00:00'
      ELSE '18:00:00'
    END
  ),
  DATE_ADD(
    TIMESTAMP(
      DATE_ADD(CURDATE(), INTERVAL day_seq.day_offset DAY),
      CASE slot_seq.slot_no
        WHEN 1 THEN '09:00:00'
        WHEN 2 THEN '13:00:00'
        ELSE '18:00:00'
      END
    ),
    INTERVAL
      CASE slot_seq.slot_no
        WHEN 1 THEN 90
        WHEN 2 THEN 120
        ELSE 150
      END MINUTE
  ),
  'ACTIVE'
FROM product_scope
CROSS JOIN day_seq
CROSS JOIN slot_seq;

INSERT INTO ticket_inventory(event_id, total, hold, sold)
SELECT
  id,
  120 + MOD(id, 320),
  MOD(id, 35),
  MOD(id, 60)
FROM ticket_event
WHERE product_id BETWEEN 300001 AND (300000 + @ticket_product_count)
ON DUPLICATE KEY UPDATE
  total = VALUES(total),
  hold = VALUES(hold),
  sold = VALUES(sold),
  updated_at = NOW(3);

SET @package_count := 500;
DELETE FROM package_product_component
WHERE package_id BETWEEN 500001 AND (500000 + @package_count);

INSERT INTO package_product(id, name, status, currency, amount_total, image_url)
WITH RECURSIVE package_seq(n) AS (
  SELECT 1
  UNION ALL
  SELECT n + 1 FROM package_seq WHERE n < @package_count
)
SELECT
  500000 + n,
  CONCAT(
    ELT(MOD(n * 3, 10) + 1, 'Seoul', 'Busan', 'Jeju', 'Tokyo', 'Osaka', 'Bangkok', 'Singapore', 'Paris', 'London', 'Sydney'),
    ' ',
    ELT(MOD(n * 9, 8) + 1, 'Weekend Explorer Package', 'Family Escape Package', 'City Culture Package', 'Food Discovery Package', 'Ocean View Package', 'Museum & Stay Package', 'Festival Night Package', 'Smart Booking Package')
  ),
  CASE WHEN MOD(n, 13) = 0 THEN 'PAUSED' ELSE 'ACTIVE' END,
  CASE
    WHEN MOD(n, 10) IN (0, 1, 2) THEN 'KRW'
    WHEN MOD(n, 10) IN (3, 4) THEN 'JPY'
    WHEN MOD(n, 10) IN (5, 6, 7) THEN 'USD'
    ELSE 'EUR'
  END,
  89000 + MOD(n * 2701, 420000),
  CONCAT('https://picsum.photos/seed/stayvista-package-rich-', n, '/640/380')
FROM package_seq
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  status = VALUES(status),
  currency = VALUES(currency),
  amount_total = VALUES(amount_total),
  image_url = VALUES(image_url),
  updated_at = NOW(3);

INSERT INTO package_product_component(
  package_id, component_type, room_type_id, ticket_event_id, nights, rooms, quantity
)
SELECT
  p.id,
  'ACCOMMODATION',
  200000 + MOD((p.id - 500000) * 37, @room_type_total) + 1,
  NULL,
  1 + MOD(p.id, 3),
  1 + MOD(p.id, 2),
  NULL
FROM package_product p
WHERE p.id BETWEEN 500001 AND (500000 + @package_count);

INSERT INTO package_product_component(
  package_id, component_type, room_type_id, ticket_event_id, nights, rooms, quantity
)
SELECT
  p.id,
  'TICKET',
  NULL,
  400000000
    + ((MOD((p.id - 500000) * 7, @ticket_product_count) + 1) * 1000)
    + (MOD((p.id - 500000) * 11, @ticket_event_days) * 10)
    + (MOD(p.id, 3) + 1),
  NULL,
  NULL,
  1 + MOD(p.id, 4)
FROM package_product p
WHERE p.id BETWEEN 500001 AND (500000 + @package_count);

UPDATE product
SET image_url = CONCAT('https://picsum.photos/seed/stayvista-ticket-', id, '/640/380')
WHERE status = 'ACTIVE'
  AND (image_url IS NULL OR image_url = '');

UPDATE package_product
SET image_url = CONCAT('https://picsum.photos/seed/stayvista-package-', id, '/640/380')
WHERE status = 'ACTIVE'
  AND (image_url IS NULL OR image_url = '');

SET @content_property_count := @property_count;
SET @content_room_type_max_id := 200000 + (@content_property_count * @room_per_property);

DELETE psi
FROM property_staycation_item psi
JOIN property_staycation_card psc ON psc.id = psi.card_id
WHERE psc.property_id BETWEEN 100001 AND (100000 + @content_property_count);
DELETE FROM property_staycation_card
WHERE property_id BETWEEN 100001 AND (100000 + @content_property_count);
DELETE FROM property_gallery_image
WHERE property_id BETWEEN 100001 AND (100000 + @content_property_count);
DELETE FROM property_highlight
WHERE property_id BETWEEN 100001 AND (100000 + @content_property_count);
DELETE FROM property_editorial
WHERE property_id BETWEEN 100001 AND (100000 + @content_property_count);

INSERT INTO property_editorial(
  property_id, short_description, long_description, check_in_time, check_out_time,
  airport_transfer_fee_krw, breakfast_fee_krw, remodeled_year, children_policy
)
SELECT
  p.id,
  CONCAT(p.city, ' ', p.district_name, ' 중심의 ', COALESCE(p.property_type_code, 'hotel'), ' 숙소입니다.'),
  CONCAT(
    p.name,
    '은(는) ',
    p.city,
    ' ',
    p.district_name,
    ' 권역에서 이동성과 접근성이 좋은 숙소입니다. ',
    '비즈니스/가족/커플 여행객 모두가 이용하기 좋은 객실 구성을 제공하며, ',
    '실시간 재고 및 요금 기준으로 예약이 확정됩니다.'
  ),
  CASE MOD(p.id, 4)
    WHEN 0 THEN '14:00'
    WHEN 1 THEN '15:00'
    WHEN 2 THEN '16:00'
    ELSE '15:30'
  END,
  CASE MOD(p.id, 3)
    WHEN 0 THEN '11:00'
    WHEN 1 THEN '11:30'
    ELSE '12:00'
  END,
  50000 + MOD(p.id * 37, 280000),
  12000 + MOD(p.id * 53, 110000),
  1998 + MOD(p.id, 27),
  CASE
    WHEN p.kid_free_stay = 1 THEN '일부 객실 타입은 아동 무료 투숙이 가능합니다.'
    ELSE '아동 동반 가능하며 객실 타입별 인원 정책이 다를 수 있습니다.'
  END
FROM property p
WHERE p.id BETWEEN 100001 AND (100000 + @content_property_count);

INSERT INTO property_highlight(property_id, content, display_order, active)
SELECT
  p.id,
  CASE hs.seq_no
    WHEN 1 THEN CONCAT(p.city, ' 핵심 이동 동선 접근 우수')
    WHEN 2 THEN CONCAT('평점 ', FORMAT(p.rating, 1), ' / 위치 ', FORMAT(p.location_rating, 1), ' 기반 인기 숙소')
    WHEN 3 THEN CONCAT('투숙객 리뷰 ', FORMAT(p.review_count, 0), '건 이상 누적')
    ELSE CONCAT('도심/주요 명소 접근성 ', IF(p.beach_distance_m < 2000, '우수', '양호'))
  END,
  hs.seq_no * 10,
  1
FROM property p
JOIN (
  SELECT 1 AS seq_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
) hs
WHERE p.id BETWEEN 100001 AND (100000 + @content_property_count);

INSERT INTO property_gallery_image(property_id, image_url, is_cover, display_order, active)
SELECT
  p.id,
  CONCAT('https://picsum.photos/seed/stayvista-property-gallery-', p.id, '-', gs.seq_no, '/960/640'),
  CASE WHEN gs.seq_no = 1 THEN 1 ELSE 0 END,
  gs.seq_no * 10,
  1
FROM property p
JOIN (
  SELECT 1 AS seq_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
) gs
WHERE p.id BETWEEN 100001 AND (100000 + @content_property_count);

INSERT INTO property_staycation_card(property_id, card_code, title, subtitle, display_order, active)
SELECT
  p.id,
  ELT(cs.seq_no, 'dining', 'wellness', 'activity', 'family'),
  ELT(cs.seq_no, '식음료', '웰니스', '즐길 거리', '가족'),
  CASE cs.seq_no
    WHEN 1 THEN '조식/라운지/레스토랑 혜택'
    WHEN 2 THEN '피트니스/스파/사우나 이용'
    WHEN 3 THEN '주변 명소/체험 추천'
    ELSE '키즈/패밀리 친화 옵션'
  END,
  cs.seq_no * 10,
  1
FROM property p
JOIN (
  SELECT 1 AS seq_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
) cs
WHERE p.id BETWEEN 100001 AND (100000 + @content_property_count);

INSERT INTO property_staycation_item(card_id, item_text, display_order, active)
SELECT
  c.id,
  CASE item_seq.seq_no
    WHEN 1 THEN CASE c.card_code WHEN 'dining' THEN '룸서비스(24시간)' WHEN 'wellness' THEN '피트니스 센터' WHEN 'activity' THEN '실내 수영장' ELSE '키즈 라운지' END
    WHEN 2 THEN CASE c.card_code WHEN 'dining' THEN '레스토랑' WHEN 'wellness' THEN '사우나' WHEN 'activity' THEN '도심 투어 제휴' ELSE '패밀리 객실 우선 배정' END
    WHEN 3 THEN CASE c.card_code WHEN 'dining' THEN '바/라운지' WHEN 'wellness' THEN '스파' WHEN 'activity' THEN '명소 할인 혜택' ELSE '아동 동반 서비스' END
    ELSE CASE c.card_code WHEN 'dining' THEN '조식 포함 옵션' WHEN 'wellness' THEN '요가 클래스' WHEN 'activity' THEN '체험 상품 번들' ELSE '유아용 편의 비품' END
  END,
  item_seq.seq_no * 10,
  1
FROM property_staycation_card c
JOIN (
  SELECT 1 AS seq_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
) item_seq
WHERE c.property_id BETWEEN 100001 AND (100000 + @content_property_count);

DELETE rpb
FROM room_rate_plan_benefit rpb
JOIN room_rate_plan rp ON rp.id = rpb.plan_id
WHERE rp.room_type_id BETWEEN 200001 AND @content_room_type_max_id;
DELETE FROM room_rate_plan
WHERE room_type_id BETWEEN 200001 AND @content_room_type_max_id;
DELETE FROM room_type_feature
WHERE room_type_id BETWEEN 200001 AND @content_room_type_max_id;
DELETE FROM room_type_media
WHERE room_type_id BETWEEN 200001 AND @content_room_type_max_id;

INSERT INTO room_type_media(room_type_id, image_url, display_order, active)
SELECT
  rt.id,
  CONCAT('https://picsum.photos/seed/stayvista-room-media-', rt.id, '-', ms.seq_no, '/720/480'),
  ms.seq_no * 10,
  1
FROM room_type rt
JOIN (
  SELECT 1 AS seq_no UNION ALL SELECT 2 UNION ALL SELECT 3
) ms
WHERE rt.id BETWEEN 200001 AND @content_room_type_max_id
  AND rt.status = 'ACTIVE';

INSERT INTO room_type_feature(room_type_id, feature_text, display_order, active)
SELECT
  rt.id,
  CASE fs.seq_no
    WHEN 1 THEN CONCAT('침대 유형 ', rt.bed_type)
    WHEN 2 THEN CONCAT('전망 ', rt.view_type)
    WHEN 3 THEN CONCAT('침실 ', rt.bedrooms, '개')
    WHEN 4 THEN CONCAT('최대 성인 ', rt.capacity_adults, '인')
    WHEN 5 THEN CASE MOD(rt.id, 2) WHEN 0 THEN '욕조' ELSE '샤워부스' END
    ELSE CASE MOD(rt.id, 3) WHEN 0 THEN '무료 Wi-Fi' WHEN 1 THEN '커피/티 메이커' ELSE '금연 객실' END
  END,
  fs.seq_no * 10,
  1
FROM room_type rt
JOIN (
  SELECT 1 AS seq_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
) fs
WHERE rt.id BETWEEN 200001 AND @content_room_type_max_id
  AND rt.status = 'ACTIVE';

INSERT INTO room_rate_plan(
  room_type_id, plan_code, occupancy_text, pay_summary, urgency_text, list_price_krw, sale_price_krw, display_order, active
)
SELECT
  rt.id,
  CASE ps.seq_no
    WHEN 1 THEN 'FLEX_CANCEL'
    WHEN 2 THEN 'PAY_LATER'
    ELSE 'BREAKFAST_SAVER'
  END,
  CONCAT('아동 ', rt.capacity_children, '명 · 투숙 무료'),
  CASE ps.seq_no
    WHEN 1 THEN '지금 예약 & 결제하기'
    WHEN 2 THEN '숙소에서 요금 결제'
    ELSE '선예약 후지불'
  END,
  CASE
    WHEN MOD(rt.id + ps.seq_no, 6) = 0 THEN '마지막 객실 임박'
    WHEN MOD(rt.id + ps.seq_no, 5) = 0 THEN '오늘 예약 급증'
    ELSE '예약 가능'
  END,
  ROUND(rt.base_price * CASE ps.seq_no WHEN 1 THEN 1.20 WHEN 2 THEN 1.12 ELSE 1.08 END),
  ROUND(rt.base_price * CASE ps.seq_no WHEN 1 THEN 1.00 WHEN 2 THEN 0.96 ELSE 0.92 END),
  ps.seq_no * 10,
  1
FROM room_type rt
JOIN (
  SELECT 1 AS seq_no UNION ALL SELECT 2 UNION ALL SELECT 3
) ps
WHERE rt.id BETWEEN 200001 AND @content_room_type_max_id
  AND rt.status = 'ACTIVE';

INSERT INTO room_rate_plan_benefit(plan_id, benefit_text, display_order, active)
SELECT
  rp.id,
  CASE bs.seq_no
    WHEN 1 THEN '무료 Wi-Fi'
    WHEN 2 THEN CASE rp.plan_code WHEN 'BREAKFAST_SAVER' THEN '조식 포함' ELSE '무료 취소 가능' END
    WHEN 3 THEN CASE rp.plan_code WHEN 'PAY_LATER' THEN '숙소에서 요금 결제' ELSE '주차' END
    ELSE CASE rp.plan_code WHEN 'FLEX_CANCEL' THEN '변경 유연 정책' ELSE '피트니스 센터' END
  END,
  bs.seq_no * 10,
  1
FROM room_rate_plan rp
JOIN (
  SELECT 1 AS seq_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
) bs
WHERE rp.room_type_id BETWEEN 200001 AND @content_room_type_max_id
  AND rp.active = 1;

UPDATE poi
SET image_urls = JSON_ARRAY(
  CONCAT('https://picsum.photos/seed/stayvista-poi-', id, '-1/640/420'),
  CONCAT('https://picsum.photos/seed/stayvista-poi-', id, '-2/640/420'),
  CONCAT('https://picsum.photos/seed/stayvista-poi-', id, '-3/640/420')
)
WHERE active = 1
  AND (image_urls IS NULL OR image_urls = '');

DELETE FROM city_poi_popular WHERE city = 'Busan';
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
    AND poi.city = 'Busan'
) ranked
WHERE rn <= 36;

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
SELECT
  city,
  id,
  CAST(GREATEST(0, 1000 - (CAST(rn AS SIGNED) * 10)) AS SIGNED) AS rank_score
FROM (
  SELECT
    p.city,
    p.id,
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
SELECT
  cmp.city,
  DATE_ADD(CURDATE(), INTERVAL ds.day_offset DAY),
  ROUND(
    cmp.min_price * (
      1 + CASE
        WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL ds.day_offset DAY)) IN (1, 7) THEN 0.10
        WHEN DAYOFWEEK(DATE_ADD(CURDATE(), INTERVAL ds.day_offset DAY)) = 6 THEN 0.07
        ELSE 0
      END
    )
  )
FROM (
  SELECT p.city, MIN(rt.base_price) AS min_price
  FROM property p
  JOIN room_type rt
    ON rt.property_id = p.id
   AND rt.status = 'ACTIVE'
  WHERE p.status = 'ACTIVE'
    AND p.city IS NOT NULL
  GROUP BY p.city
) cmp
CROSS JOIN (
  SELECT (u.n + t.n * 10 + h.n * 100) AS day_offset
  FROM (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
  ) u
  CROSS JOIN (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
  ) t
  CROSS JOIN (
    SELECT 0 AS n UNION ALL SELECT 1
  ) h
  WHERE (u.n + t.n * 10 + h.n * 100) < 120
) ds
;

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
