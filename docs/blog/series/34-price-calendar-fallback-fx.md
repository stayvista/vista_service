---
title: "StayVista 기술 개발기 34: [핵심] Price Calendar - Place 정규화, City Fallback, FX 변환까지 한 번에 처리하기"
slug: "34-price-calendar-fallback-fx"
series: "StayVista 기술 개발기"
order: 34
prev_slug: "33-facet-engine-server-driven"
next_slug: "36-search-copilot-filters"
status: "publish-ready"
excerpt: "가격 캘린더 API의 핵심은 단순 조회가 아니라 \"다양한 place 입력을 표준화하고, 데이터가 비어도 일관된 응답을 보장\"하는 것입니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 34: [핵심] Price Calendar - Place 정규화, City Fallback, FX 변환까지 한 번에 처리하기

## 한 줄 요약
가격 캘린더 API의 핵심은 단순 조회가 아니라 "다양한 place 입력을 표준화하고, 데이터가 비어도 일관된 응답을 보장"하는 것입니다.

## 문제 배경
캘린더는 사용자 입력 변형이 많습니다.

- city 문자열 직접 입력
- `place_id` 인코딩값(CITY/PROPERTY/POI)
- POI 기준 조회
- 통화 변환 요구

로컬 검증에서 중요한 건 어떤 입력이 와도 응답 스키마/범위를 안정적으로 맞추는 것입니다.

## PriceCalendarService 구조
`calendar(request)` 흐름:

1. 입력 정규화 (`normalize`)
2. place 해석 (`resolvePlace`)
3. 캐시 조회
4. place type별 base day 로딩
5. KRW -> 요청 통화 환산 (`FxService`)
6. `PriceCalendarData` 응답 + 캐시 저장

## 핵심 구현 포인트
### 1) 입력 정규화
`PriceCalendarRequest.normalize()`에서:

- 기간 역전 방지 (`to < from` 보정)
- 최대 기간 93일 제한
- guests 파라미터 상한 강제
- currency uppercase

API 경계에서 입력 범위를 제한해 쿼리 비용 폭주를 막습니다.

### 2) place 타입별 분기
- `PROPERTY`: room_type의 min(base_price) 기반 일자 확장
- `CITY`: `city_day_min_price` 우선
- `POI`: POI의 city를 먼저 해석 후 city 경로 재사용
- `STATION/AIRPORT`: 현재 empty

### 3) city fallback
`city_day_min_price`가 비면 property+room_type에서 fallback min_price를 계산해 날짜 시리즈를 채웁니다.
즉 데이터가 비어도 response days는 유지됩니다.

### 4) FX 변환
모든 base 가격은 KRW 기준이며, 응답 직전에 `FxService.convert(...)`로 통화를 맞춥니다.
통화 변환 책임을 서비스에서 일관되게 처리합니다.

## 캐시 전략
캐시 키는 place/date/currency와 guests 해시를 포함합니다.

- TTL 600초
- hit/miss 메트릭 분리
- place_type 태그로 성능/품질 분석 가능

## 기술적으로 중요한 포인트
### 1) place 해석 실패를 city fallback으로 수렴
`PlaceIdCodec.parseOrNull` 실패 시에도 city canonicalize 경로를 타도록 설계했습니다.
입력 다양성을 서비스 내부에서 흡수합니다.

### 2) 캘린더 응답은 "빈 배열"보다 "available=false 시계열"이 낫다
프론트 렌더링/비교 흐름이 안정화되고, degrade UX가 단순해집니다.

### 3) nights/meta는 항상 계산해 전달
데이터가 부족해도 메타 정보가 일관되면 downstream 로직이 단순해집니다.

## 로컬 검증 지표
- `price_calendar_requests_total{cache,place_type}`
- `price_calendar_latency_ms{cache,place_type}`
- `price_calendar_available_days_ratio{place_type}`

핵심은 latency만이 아니라 `available_days_ratio`의 급락 감지합니다.

## 개선 과제
- station/airport 집계 경로 추가
- city_day_min_price 백필 파이프라인 자동화
- FX 변환 실패 시 통화 fallback 정책 명시화

가격 캘린더는 검색 기능이 아니라 "가격 일관성을 보장하는 기반 API"에 가깝습니다.
