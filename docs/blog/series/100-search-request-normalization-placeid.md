---
title: "StayVista 기술 개발기 100: [심화] Search 요청 정규화 - place_id 해석과 가용성 파라미터 검증"
slug: "100-search-request-normalization-placeid"
series: "StayVista 기술 개발기"
order: 100
prev_slug: "99-domain-support-outbox-helpers"
next_slug: null
status: "publish-ready"
excerpt: "검색 정확도는 쿼리 실행 전에 이미 절반이 결정됩니다. StayVista는 `SearchRequest.normalize`와 `resolvePlaceFilter`에서 place/city/filters를 먼저 정규화하고, 날짜/박수 검증으로 가용성 조건을 명확히 고정했습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 100: [심화] Search 요청 정규화 - place_id 해석과 가용성 파라미터 검증

## 한 줄 요약
검색 쿼리를 잘 짜는 것만큼 중요한 것이 입력 정규화입니다. 입력이 흔들리면 같은 의도라도 다른 결과가 나옵니다.

## 1) `SearchRequest.normalize`
요청 객체에서 먼저 값 범위와 포맷을 보정합니다.

- `city` canonicalize
- `place_id` trim
- `rooms/adults/children` 범위 보정
- `currency` 대문자화
- 리스트 필터 dedupe/sort
- `limit/size/page` 상한/하한 보정

이 단계가 있어야 캐시 키 생성(`search:v2:sha256(...)`)도 안정됩니다.

구체적으로 `limit/size`는 최대 50, `rooms` 최대 8, `adults` 최대 16, `children` 최대 8로 제한합니다.

## 2) place_id 해석 (`resolvePlaceFilter`)
`PlaceIdCodec.parseOrNull` 결과에 따라 필터를 재작성합니다.

- `CITY`: `city`로 변환, `property_id` 제거
- `PROPERTY`: numeric canonical_id 강제, 실패 시 `VALIDATION_ERROR`
- `POI`: poi.city 역조회 후 city 필터 대체
- `STATION/AIRPORT`: 현재는 추가 변환 없이 유지

즉 `city`와 `property_id`가 동시에 충돌하지 않도록 우선순위를 정리합니다.

`PlaceIdCodec` 자체도 입력 형식을 강제합니다.

- `type:canonical_id` 형식이 아니면 `VALIDATION_ERROR`
- 지원하지 않는 type이면 `VALIDATION_ERROR`
- canonical_id가 비어 있으면 `VALIDATION_ERROR`

## 3) 가용성 파라미터 검증 (`resolveAvailabilityFilter`)
가용성 필터는 아래를 강제합니다.

- `check_in`, `check_out` 동시 입력
- ISO date 파싱
- `check_out > check_in`
- 최대 30박
- rooms 최소 1

검증 실패는 모두 `VALIDATION_ERROR`로 통일합니다.

박수 계산은 `DateRange.nights(checkIn, checkOut)`를 사용해 경계값(체크인/체크아웃 역전)을 일관되게 처리합니다.

## 4) OpenSearch 게이트 조건
`canUseOpenSearch`는 일부 요청만 OpenSearch 경로로 보냅니다.

예를 들어 `page/size`, `rooms`, `children_ages`, 평점/거리 밴드, 편의시설/테마/브랜드 등 고급 필터가 하나라도 있으면 DB 경로로 고정됩니다.

이 분기 덕분에 OpenSearch 인덱스가 아직 커버하지 못한 조건도 안전하게 처리할 수 있습니다.

## 5) 필터 사용량 계측
정규화된 요청에서 활성 필터 목록을 추출해
`search_filter_usage_total`과 `search_active_filter_count`를 기록합니다.

이 값으로 어떤 필터 조합이 실제로 쓰이는지 확인할 수 있습니다.

## 테스트 근거
`SearchServiceTest`
- `place_id=property:*` 우선 적용
- `place_id=poi:*` city 변환
- 한글 도시명 정규화
- 날짜 역전/가용성 검증 실패

`SearchServiceOpenSearchFallbackTest`
- OpenSearch empty 결과 시 DB fallback 경로 검증

## 기술적으로 중요한 포인트
- 입력 정규화가 흔들리면 캐시 적중률과 결과 일관성이 동시에 악화됩니다.
- place 해석 우선순위를 명시해야 도시/숙소 필터 충돌이 줄어듭니다.
- 가용성 검증은 SQL 이전 단계에서 실패시켜야 불필요한 DB 부하를 줄일 수 있습니다.
- OpenSearch 사용 조건을 명시적으로 제한해야 검색 결과 불일치를 줄일 수 있습니다.

## 남은 과제
- station/airport -> city 매핑 고도화
- availability window 기반 사전 캐시 키 분리
- 필터 조합별 성능 프로파일 자동 수집
