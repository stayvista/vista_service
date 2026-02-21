ALTER TABLE property
  ADD COLUMN review_count INT NOT NULL DEFAULT 0,
  ADD COLUMN beach_distance_m INT NULL,
  ADD COLUMN is_beachfront TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN kid_free_stay TINYINT(1) NOT NULL DEFAULT 0;

CREATE INDEX idx_property_city_beach ON property(city, is_beachfront, beach_distance_m);
CREATE INDEX idx_property_city_family ON property(city, kid_free_stay);
CREATE INDEX idx_property_city_review ON property(city, review_count);

ALTER TABLE room_type
  ADD COLUMN bedrooms INT NOT NULL DEFAULT 1;

CREATE INDEX idx_room_type_filter ON room_type(property_id, status, bed_type, bedrooms);

INSERT INTO property_type(code, label_ko, label_en) VALUES
  ('hotel', '호텔', 'Hotel'),
  ('resort', '리조트', 'Resort'),
  ('guesthouse', '게스트하우스/비앤비', 'Guesthouse / BnB'),
  ('motel', '모텔', 'Motel'),
  ('hostel', '호스텔', 'Hostel'),
  ('apartment', '아파트', 'Apartment'),
  ('serviced_apartment', '서비스 아파트', 'Serviced Apartment'),
  ('homestay', '홈스테이', 'Homestay'),
  ('inn', '인', 'Inn'),
  ('resort_villa', '리조트 빌라', 'Resort Villa'),
  ('pension', '펜션', 'Pension'),
  ('private_house', '프라이빗 하우스 전체', 'Entire Private House'),
  ('capsule_hotel', '캡슐 호텔', 'Capsule Hotel'),
  ('holiday_park', '홀리데이 파크/카라반 파크', 'Holiday Park / Caravan Park'),
  ('villa', '빌라', 'Villa'),
  ('lodge', '로지', 'Lodge'),
  ('bungalow', '방갈로', 'Bungalow'),
  ('boutique', '부티크', 'Boutique')
ON DUPLICATE KEY UPDATE
  label_ko = VALUES(label_ko),
  label_en = VALUES(label_en);

INSERT INTO amenity(code, label_ko, label_en, group_code) VALUES
  ('fridge', '냉장고', 'Refrigerator', 'room_facility'),
  ('air_conditioning', '에어컨', 'Air Conditioning', 'room_facility'),
  ('tv', 'TV', 'TV', 'room_facility'),
  ('heating', '난방', 'Heating', 'room_facility'),
  ('parking', '주차장', 'Parking', 'property_facility'),
  ('internet', '인터넷', 'Internet', 'property_facility'),
  ('breakfast', '조식 포함', 'Breakfast Included', 'service_option'),
  ('food_delivery_external', '외부 배달 음식 허용', 'External Food Delivery Allowed', 'service_option'),
  ('family_delivery_allowed', '가족 및 친척의 배달 허용', 'Family Delivery Allowed', 'service_option'),
  ('early_checkin', '얼리 체크인', 'Early Check-in', 'service_option'),
  ('espresso_machine', '에스프레소 머신 및 파드', 'Espresso Machine and Pods', 'service_option'),
  ('late_checkout', '레이트 체크아웃', 'Late Check-out', 'service_option'),
  ('convenience_delivery', '근처 편의점에서 배달 가능', 'Convenience Store Delivery', 'service_option'),
  ('free_snack', '무료 스낵', 'Free Snack', 'service_option'),
  ('airport_transfer', '공항 이동 교통편 서비스', 'Airport Transfer', 'service_option'),
  ('treadmill', '러닝머신', 'Treadmill', 'service_option'),
  ('dinner_included', '석식 포함', 'Dinner Included', 'service_option'),
  ('afternoon_tea', '애프터눈 티', 'Afternoon Tea', 'service_option'),
  ('pool', '수영장', 'Pool', 'property_facility'),
  ('gym', '체육관/피트니스', 'Gym / Fitness', 'property_facility'),
  ('frontdesk_24h', '24시간 프런트데스크', '24h Front Desk', 'property_facility'),
  ('family_friendly', '가족/아동 여행객 친화형 시설', 'Family Friendly', 'property_facility'),
  ('non_smoking', '금연', 'Non-smoking', 'property_facility'),
  ('spa', '스파/사우나', 'Spa / Sauna', 'property_facility'),
  ('restaurant', '레스토랑', 'Restaurant', 'property_facility'),
  ('smoking_area', '흡연 구역', 'Smoking Area', 'property_facility'),
  ('pet_friendly', '반려동물 동반 가능', 'Pet Friendly', 'property_facility'),
  ('nightclub', '나이트 클럽', 'Night Club', 'property_facility'),
  ('accessible', '장애인용 편의 시설/서비스', 'Accessible Facilities', 'property_facility'),
  ('golf_course', '골프장', 'Golf Course', 'property_facility'),
  ('washer', '세탁기', 'Washer', 'room_facility'),
  ('coffee_maker', '커피/티 메이커', 'Coffee/Tea Maker', 'room_facility'),
  ('bathtub', '욕조', 'Bathtub', 'room_facility'),
  ('toiletries', '다리질 도구', 'Toiletries / Ironing Kit', 'room_facility'),
  ('kitchen', '주방', 'Kitchen', 'room_facility'),
  ('balcony', '발코니/테라스', 'Balcony / Terrace', 'room_facility'),
  ('private_pool', '전용 수영장', 'Private Pool', 'room_facility'),
  ('wifi', '와이파이', 'Wi-Fi', 'property_facility'),
  ('ocean_view', '오션뷰', 'Ocean View', 'room_facility')
ON DUPLICATE KEY UPDATE
  label_ko = VALUES(label_ko),
  label_en = VALUES(label_en),
  group_code = VALUES(group_code);

INSERT INTO payment_option(code, label_ko, group_code) VALUES
  ('free_cancel', '예약 무료 취소', 'policy'),
  ('pay_at_property', '숙소에서 요금 결제', 'timing'),
  ('reserve_now_pay_later', '선예약 후지불', 'timing'),
  ('pay_now', '지금 바로 결제', 'timing'),
  ('no_credit_card', '신용카드 없이 예약 가능', 'policy'),
  ('pay_later', '숙소 결제', 'timing'),
  ('no_prepay', '선결제 없음', 'policy')
ON DUPLICATE KEY UPDATE
  label_ko = VALUES(label_ko),
  group_code = VALUES(group_code);

INSERT INTO theme(code, label_ko) VALUES
  ('family', '가족 여행객 친화형'),
  ('group', '그룹/단체 여행객 친화형'),
  ('workation', '워케이션 친화형'),
  ('pet', '반려동물 동반 가능'),
  ('business', '비즈니스'),
  ('shopping', '쇼핑'),
  ('nature', '자연'),
  ('romance', '커플')
ON DUPLICATE KEY UPDATE
  label_ko = VALUES(label_ko);
