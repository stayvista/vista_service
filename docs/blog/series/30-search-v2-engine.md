---
title: "StayVista 기술 개발기 30: Search v2 엔진 - 필터/정렬/가용성 조건을 한 번에 다루는 쿼리 설계"
slug: "30-search-v2-engine"
series: "StayVista 기술 개발기"
order: 30
prev_slug: "19-rate-limit-abuse"
next_slug: "31-opensearch-fallback"
status: "publish-ready"
excerpt: "검색 API는 단순 조회가 아니라 \"비즈니스 룰 집합\"입니다. 필터가 늘어날수록 정합성·성능·유지보수성 사이의 균형이 핵심이 됩니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 30: Search v2 엔진 - 필터/정렬/가용성 조건을 한 번에 다루는 쿼리 설계

## 한 줄 요약
검색 API는 단순 조회가 아니라 "비즈니스 룰 집합"입니다. 필터가 늘어날수록 정합성·성능·유지보수성 사이의 균형이 핵심이 됩니다.

## 요구사항 복잡도
`/v1/search/properties`는 다음을 동시에 지원합니다.

- 기본 검색: q/city/place_id
- 가격/평점/거리/별점/편의시설/숙소타입/브랜드/테마
- 가족/해변 옵션
- 주변 명소 조건
- 정렬(price/rating/distance/best_match)
- 페이지/커서
- 통화 변환
- 재고 가용성(check_in/check_out/rooms)

## 구현 핵심
### 1) 요청 정규화
`SearchRequest.normalize()`에서:
- city canonicalization
- 리스트 중복 제거
- 값 범위 보정(rooms, stars, bedrooms 등)
- currency 표준화

입력 정규화가 선행되어야 캐시 키와 쿼리 결과가 안정됩니다.

### 2) 동적 WHERE + EXISTS
필터별로 EXISTS 서브쿼리를 조합합니다.

- room_type 조건
- amenity/theme/payment_option
- brand join 조건
- nearby attraction 거리 조건

복잡한 join을 한 번에 폭발시키기보다, 조건 단위 EXISTS로 분리해 의미를 명확히 했습니다.

### 3) 가용성 필터
날짜/객실 수가 주어지면:
- `inventory_night`를 구간으로 조회
- `HAVING COUNT(*) = nights`
- `MIN(total-hold-sold) >= rooms`

즉 "모든 날짜에 충분한 재고가 있는 룸타입이 하나라도 존재"해야 검색 결과에 포함됩니다.

### 4) 거리 기반 정렬/필터
center가 있으면 Haversine 식을 SQL에 포함합니다.
- distance band
- max_distance_m
- distance sort

## 성능 관점 포인트
### 1) 결과 캐시
- 키: 정규화된 요청 문자열의 sha256
- TTL: 10초
- hit/miss 메트릭 분리

### 2) 필터 사용량 계측
- `search_active_filter_count`
- `search_filter_usage_total{filter=*}`

느린 쿼리 최적화 우선순위를 실제 사용량 데이터로 정합니다.

### 3) 통화 변환 분리
DB는 KRW 기준 가격을 유지하고, 응답 직전에 FX 변환합니다.
가격 저장 정합성과 표시 통화 요구를 분리한 구조입니다.

## 기술적으로 중요한 포인트
- "필터 추가"는 곧 쿼리 계획 변경이므로 메트릭 없이 추가하면 위험합니다.
- 가용성 필터는 검색 품질 기능이 아니라 정합성 기능입니다.
- 커서/페이지를 혼용할 때 조건 충돌을 명확히 해야 합니다.

## 남은 개선 과제
- 고비용 필터 조합에 대한 쿼리 플랜 자동 검증
- 인기 필터 조합 프리컴퓨팅/캐시 고도화
- OpenSearch 경로와 DB 경로 간 결과 일관성 검증 자동화

