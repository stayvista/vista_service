---
title: "StayVista 기술 개발기 87: [확장] Catalog 상세 조회 - 객실 가용성 계산과 리뷰 집계 쿼리"
slug: "87-catalog-roomtype-review-queries"
series: "StayVista 기술 개발기"
order: 87
prev_slug: "86-destination-recommendation-fallback"
next_slug: "88-payment-gateway-failure-contract"
status: "publish-ready"
excerpt: "숙소 상세는 단순 조회가 아니라 재고/hold/review를 함께 계산해야 합니다. StayVista는 `listRoomTypes`와 `listPropertyReviews`를 분리해 가용성 계산과 리뷰 집계를 명확하게 구현했습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 87: [확장] Catalog 상세 조회 - 객실 가용성 계산과 리뷰 집계 쿼리

## 한 줄 요약
상세 페이지 응답은 테이블 단순 조인이 아니라 "질문에 맞는 계산"이 필요했습니다. 객실은 날짜 기반 가용성 계산, 리뷰는 요약/태그/페이지네이션을 분리해 처리했습니다.

## 객실 가용성 (`listRoomTypes`)
### 날짜 파라미터 검증
`resolveAvailabilityWindow`에서 아래를 강제합니다.

- `check_in`/`check_out` 동시 입력
- `check_out > check_in`
- 최대 30박

날짜가 없으면 가용성 계산 없이 객실 기본 정보만 반환합니다.

### 가용성 계산 SQL
날짜가 있을 때는 `inventory_night`를 집계해 객실별 최소 잔여 수량을 계산합니다.

- `available_rooms = MIN(total - hold - sold)`
- `covered_nights = COUNT(*)`
- `is_available = covered_nights == nights && available_rooms >= requested_rooms`

즉 일부 날짜만 재고가 있어도 가용으로 판단하지 않습니다.

### 사용자 hold 메타데이터 병합
동일 user/check-in/check-out/rooms 조건의 active HOLD를 LEFT JOIN으로 붙입니다.

- `active_hold_booking_id`
- `active_hold_expires_at`

이 정보로 프론트에서 "내가 이미 hold 중인 객실"을 별도 표시할 수 있습니다.

## 리뷰 집계 (`listPropertyReviews`)
### 1) property 존재 검증
property가 없으면 즉시 `NOT_FOUND`를 반환합니다.

### 2) 요약 집계
`property_review`에서 다음 평균/카운트를 계산합니다.

- `avg_score`
- `service`, `cleanliness`, `facility`, `value_for_money`, `location`
- `total`

### 3) 태그 집계
`property_review_tag`를 조인해 태그별 카운트를 계산합니다.

### 4) 목록 조회
- optional `tag` 필터(서브쿼리 `EXISTS`)
- 정렬: `stay_date DESC, id DESC`
- 페이지네이션: `LIMIT/OFFSET`

목록 조회 후 review ID 집합으로 태그를 다시 조회해 item에 합칩니다.

## 테스트 근거
- `CatalogServiceRoomTypeAvailabilityTest`
  - 날짜 범위 가용성 계산
  - 사용자 hold 메타데이터 포함 여부
  - 잘못된 날짜 입력 검증
- `CatalogServiceReviewTest`
  - 리뷰 요약/태그 집계
  - 태그 필터
  - property 미존재 예외
- `CatalogControllerRoomTypeQueryBindingTest`
  - `check_in/check_out` snake_case 바인딩

## 기술적으로 중요한 포인트
- 객실 가용성은 "한 날짜라도 부족하면 불가"라는 규칙을 SQL 집계로 직접 표현해야 합니다.
- 리뷰는 summary/tags/items를 분리해 쿼리해야 확장 시 성능/유지보수성이 좋아집니다.
- 컨트롤러 바인딩 테스트를 같이 두면 API 파라미터 회귀를 조기에 막을 수 있습니다.
