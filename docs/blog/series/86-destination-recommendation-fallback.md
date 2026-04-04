---
title: "StayVista 기술 개발기 86: [확장] Destination 추천 엔진 - city/place 해석과 다단 fallback"
slug: "86-destination-recommendation-fallback"
series: "StayVista 기술 개발기"
order: 86
prev_slug: "85-search-index-sync-outbox-projection"
next_slug: "87-catalog-roomtype-review-queries"
status: "publish-ready"
excerpt: "추천 API는 입력이 불완전해도 일관된 응답을 반환해야 합니다. StayVista는 `city_id/place_id`를 정규화한 뒤 district/poi/featured/country-cities를 단계적으로 fallback해 결과를 구성했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 86: [확장] Destination 추천 엔진 - city/place 해석과 다단 fallback

## 한 줄 요약
Destination 추천의 핵심은 "좋은 후보를 뽑는 것"보다 "입력이 불완전해도 빈 화면을 만들지 않는 것"이었습니다.

## 엔드포인트
- `GET /v1/destinations/recommendations`
- 파라미터: `city_id`, `place_id`, `lang`, `limit`
- `limit`는 `4..24`로 보정합니다.

## 1) 도시 해석 단계 (`resolveCity`)
우선순위는 아래와 같습니다.

1. `city_id` 직접 사용 (canonicalize 적용)
2. `place_id`가 `type:canonical` 형식이 아니면 city 문자열로 해석
3. `place_id`가 `CITY:*`면 canonical ID 사용
4. 모두 실패하면 기본값 `Seoul`

`PlaceIdCodec` + `CityCanonicalizer`를 함께 써서 입력 형태를 통일했습니다.

## 2) 국가 해석 단계 (`resolveCountry`)
먼저 실제 데이터에서 추론하고, 없으면 룰 기반으로 fallback합니다.

- 1차: `property`에서 `city + ACTIVE + country` 조회
- 2차: city 매핑 룰(`Seoul/Busan/Jeju -> KR`, `Tokyo/Osaka/Kyoto -> JP`)
- 3차: 기본 `KR`

## 3) 추천 블록별 로딩 규칙
응답은 4개 블록을 병렬 개념으로 구성합니다.

### districts
- 우선: `district` 테이블 rank
- fallback: `property.district_name` 집계

### pois
- 우선: `city_poi_popular` + `poi`
- fallback: `poi.popularity_score`, `rating_score` 기반 정렬

### featured_properties
- 우선: `city_featured_property` + `property`
- fallback: `property.rating`, `popularity_score` 기반 정렬

### country_popular_cities
- 우선: 같은 국가의 active property 집계
- fallback: 기본 도시 목록(`Seoul`, `Busan`, `Jeju`)

## 출력 안정화를 위해 넣은 장치
- city/country가 비어도 기본값으로 채웁니다.
- 블록별 후보가 비어도 다른 블록은 정상 반환합니다.
- 입력 `limit`가 과도해도 상한(24)으로 고정합니다.

## 기술적으로 중요한 포인트
- 추천 API에서 fallback은 예외 처리가 아니라 기본 설계입니다.
- city/place 정규화가 먼저 고정되지 않으면 하위 쿼리 결과가 흔들립니다.
- 블록 단위 분리를 해두면 한 소스가 비어도 전체 응답 품질을 유지할 수 있습니다.

## 남은 과제
- `DestinationRecommendationService` 전용 테스트 추가
- `lang`별 라벨/문구 분기 강화
- fallback 발생 비율을 계측해 데이터 보강 우선순위에 반영
