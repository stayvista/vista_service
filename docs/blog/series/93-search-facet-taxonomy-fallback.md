---
title: "StayVista 기술 개발기 93: [심화] Search Facet 엔진 - 택소노미 보존과 도시별 fallback"
slug: "93-search-facet-taxonomy-fallback"
series: "StayVista 기술 개발기"
order: 93
prev_slug: "92-api-envelope-error-contract"
next_slug: "94-auth-password-session-hardening"
status: "publish-ready"
excerpt: "Facet API는 데이터가 비어도 필터 구조가 유지되어야 합니다. StayVista는 `SearchFacetService`에서 택소노미 테이블을 기준으로 zero-count 항목까지 반환하고, city/place 해석과 fallback 쿼리를 결합해 안정적인 필터 응답을 만들었습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 93: [심화] Search Facet 엔진 - 택소노미 보존과 도시별 fallback

## 한 줄 요약
Facet의 목표는 "많이 나오는 값"만 반환하는 것이 아니라, 프론트가 기대하는 필터 구조를 안정적으로 유지하는 것입니다.

## 입력 해석
`SearchFacetService.facets(placeId, city)`는 먼저 도시를 해석합니다.

- `city` 직접 입력
- plain `place_id` 문자열
- `place_id=city:*`
- `place_id=poi:*`면 poi.city 역조회

`PlaceIdCodec` + `CityCanonicalizer`를 함께 사용해 입력 변형을 흡수합니다.

## 그룹 계산 전략
각 facet 그룹은 `safeList`로 감싸 한 그룹 실패가 전체 실패로 번지지 않게 했습니다.

반환 그룹:
- `popular_filters`, `districts`, `nearby_attractions`, `brands`
- `amenity_groups`, `stars`, `property_types`, `payment_options`, `themes`
- `amenities`, `guest_rating_bands`, `location_rating_bands`, `distance_bands`
- `bed_types`, `bedrooms`, `family_options`, `beach_options`

## 택소노미 우선 반환
`property_type`, `payment_option`, `theme`, `amenity`는 집계 테이블이 아니라 기준 테이블을 LEFT JOIN으로 조회합니다.

즉 실제 count가 0이어도 항목 자체는 유지됩니다.

장점:
- 프론트 필터 렌더링 구조가 흔들리지 않음
- 신규 데이터가 없어도 필터 옵션 계약 유지

## fallback 규칙
### districts
- 우선: `district` 테이블 rank
- fallback: `property.district_name` 집계

### nearby attractions
- 우선: `city_poi_popular`
- fallback: `poi.popularity_score`

### amenity group
legacy group code(`essential`, `dining`, `room`)를 표준 그룹으로 보정해 묶습니다.

## 계측
- `search_facets_requests_total{scope=city|global}`
- `search_facets_empty_group_count`
- `search_facets_latency_ms`

빈 그룹 수를 별도로 기록해 데이터 결손과 쿼리 실패를 구분할 수 있게 했습니다.

## 테스트 근거
`SearchFacetServiceTest`에서 다음을 검증합니다.

- zero-count 택소노미 항목 유지
- 한글 도시명 정규화
- legacy amenity group code 보정

## 기술적으로 중요한 포인트
- Facet API는 결과 개수보다 구조 안정성이 더 중요합니다.
- 기준 테이블 기반 LEFT JOIN이 있어야 zero-count 옵션을 안전하게 유지할 수 있습니다.
- 그룹별 fallback을 분리해야 데이터 편차가 큰 도시에서도 응답 품질을 유지할 수 있습니다.

## 남은 과제
- facet 그룹별 캐시 전략 분리
- 그룹 단위 부분 갱신 API
- 필터 클릭률 기반 정렬 가중치 실험
