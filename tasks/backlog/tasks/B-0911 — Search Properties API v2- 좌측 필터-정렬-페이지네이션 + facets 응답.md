# B-0911 — Search Properties API v2

## Goal
검색 결과 화면의 메인 API 및 필터 facets 제공.

## API
- GET /v1/search/properties
  - place_id, check_in, check_out, rooms, adults, children, children_ages, currency
  - sort, page, size
  - filters: price_range, stars, guest_rating, location_rating, amenities, property_type, districts, payment_options, themes, brands, distance_to_center, bed_types, bedrooms, etc

## Response
- items[] (카드에 필요한 모든 정보)
- facets (city별 인기필터/지역/브랜드/등급 등) v1: subset ok
- meta(total,took_ms)

## Acceptance Criteria
- URL로 재현 가능한 검색
- p95 < 300ms 목표(스텁/캐시 포함)
