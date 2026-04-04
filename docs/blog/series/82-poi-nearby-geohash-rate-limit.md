---
title: "StayVista 기술 개발기 82: [확장] POI Nearby 엔진 - geohash 후보 축소, 정렬 전략, 토큰버킷 제한"
slug: "82-poi-nearby-geohash-rate-limit"
series: "StayVista 기술 개발기"
order: 82
prev_slug: "81-package-saga-compensation"
next_slug: "83-my-reservation-inquiry-apis"
status: "publish-ready"
excerpt: "Nearby 검색은 bbox가 커지면 쉽게 느려집니다. StayVista는 geohash prefix 후보 축소와 캐시 키 정규화, 그리고 endpoint 전용 토큰버킷 제한으로 응답 성능을 안정화했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 82: [확장] POI Nearby 엔진 - geohash 후보 축소, 정렬 전략, 토큰버킷 제한

## 한 줄 요약
Nearby API는 단순 반경 검색이 아니라, 후보 축소(geohash) + 정렬 정책 + 트래픽 제한을 함께 설계해야 일관된 응답 시간을 유지할 수 있습니다.

## 스키마 확장 (`V8__poi_geo_nearby_v2.sql`)
`poi` 테이블에 아래 필드를 추가했습니다.

- `active`
- `address`, `description`, `image_urls`
- `popularity_score`, `rating_score`
- `geohash`

인덱스:
- `idx_poi_active_lat_lng`
- `idx_poi_active_category_geohash`

## Nearby 조회 파이프라인 (`PoiService.nearby`)
1. bbox/center/radius/sort 정규화
2. cache key 생성(`sha256`) 후 캐시 조회
3. 후보 로드
   - lat/lng bbox 범위
   - category 필터
   - geohash prefix 조건
4. 거리 계산(haversine) + 정렬
5. `offset + limit + 1` 기반 페이지네이션(`has_more` 계산)

## geohash prefix 전략
`PoiGeohashPrefixPlanner`가 bbox에 맞는 prefix를 계산하고, SQL에서 `geohash LIKE 'prefix%'`로 후보 범위를 줄입니다.

다만 `geohash`가 비어 있는 과거 데이터도 포함되도록 아래 조건을 같이 둡니다.
- `geohash IS NULL OR geohash = '' OR geohash LIKE ...`

그래서 백필 이전 데이터도 누락되지 않습니다.

## 정렬 정책
`sort` 옵션은 3가지입니다.

- `distance`
- `popularity`
- `rating`

정렬 tie-breaker를 명시해 결과 안정성을 확보했습니다.

## 캐시와 무효화
- nearby 캐시 TTL: `stayvista.poi.nearby.cache-ttl-seconds` (기본 15초)
- 캐시 키에 bbox/category/sort/limit/offset/radius를 모두 포함
- admin 생성/수정/백필 후 `cache.invalidatePrefix("nearby:")`

## 트래픽 제어
`TrafficGuardFilter`에서 `/v1/poi/nearby`는 전용 `NearbyTokenBucketRateLimiter`로 제한합니다.

- refill/burst 파라미터 분리
- bot 시그니처(User-Agent) 감지 시 더 엄격한 principal 키 사용
- 초과 시 `Retry-After` 반환

## 관련 테스트
- `PoiServiceTest`
  - active 필터, pagination meta, detail 링크/연관 힌트 검증
  - geohash 누락 데이터 포함 검증
  - backfill 후 geohash 채움 검증
- `NearbyTokenBucketRateLimiterTest`
  - burst 소진/재충전 타이밍 검증

## 기술적으로 중요한 포인트
### 1) bbox 기반 후보 축소와 거리 계산을 분리했습니다
DB는 후보를 줄이고, 최종 거리 계산/정렬은 애플리케이션에서 수행해 구현 복잡도를 낮췄습니다.

### 2) radius는 viewport가 지나치게 넓을 때 비활성화합니다
광역 bbox에서 반경 필터를 억지 적용하면 오히려 탐색 품질이 떨어집니다.

### 3) admin 변경과 캐시 무효화를 같은 흐름에 묶었습니다
데이터 수정 후 stale nearby 응답을 빠르게 제거할 수 있습니다.

## 남은 과제
- 대규모 트래픽에서 DB 거리 정렬 오프로드 검토
- category 계층형 필터 확장
- geohash 백필 자동 배치 주기화
