---
title: "StayVista 기술 개발기 33: Facet Engine - 서버 주도 필터 구성을 선택한 이유"
slug: "33-facet-engine-server-driven"
series: "StayVista 기술 개발기"
order: 33
prev_slug: "31-opensearch-fallback"
next_slug: "34-price-calendar-fallback-fx"
status: "publish-ready"
excerpt: "필터를 클라이언트 하드코딩으로 두면 확장이 느리고 불일치가 늘어납니다. Facet을 서버가 내려주는 구조로 전환해 개발 안정성을 확보했습니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 33: Facet Engine - 서버 주도 필터 구성을 선택한 이유

## 한 줄 요약
필터를 클라이언트 하드코딩으로 두면 확장이 느리고 불일치가 늘어납니다. Facet을 서버가 내려주는 구조로 전환해 개발 안정성을 확보했습니다.

## Facet API 역할
`/v1/search/facets`는 단순 목록 API가 아니라,
검색 UI의 좌측 패널 구조를 서버에서 조립해 전달하는 역할을 합니다.

응답 그룹 예시:
- popular_filters
- districts
- nearby_attractions
- brands
- amenity_groups
- stars/property_types/payment_options/themes
- rating/distance/bed/family/beach 옵션

## 구현 포인트
### 1) city/place_id 해석
`place_id`가 CITY/POI일 때 city를 해석해 지역 스코프를 맞춥니다.
UI 파라미터 표현이 달라도 서버에서 일관된 city 기준으로 정규화합니다.

### 2) 안전 조회(safeList)
facet 그룹별 조회는 `safeList`로 감싸 부분 실패를 허용합니다.
한 그룹 실패가 전체 facet 실패로 번지지 않게 했습니다.

### 3) fallback 설계
예를 들어 district는:
- 우선 `district` 테이블 기반
- 데이터가 없으면 `property` 집계로 fallback

데이터 이행 중에도 UI가 깨지지 않게 만든 구조입니다.

## 관측성
Facet API는 아래 지표를 기록합니다.

- `search_facets_requests_total{scope=global|city}`
- `search_facets_empty_group_count`
- `search_facets_latency_ms`

특히 `empty_group_count`는 데이터 품질 회귀를 빨리 잡는 지표입니다.

## 기술적으로 중요한 포인트
### 1) 서버 주도 구성이 주는 이점
- UI 재빌드 없이 필터 정책 변경 가능
- 지역별/시기별 필터 실험 가능
- 개발자 주도 튜닝 용이

### 2) 그룹 단위 장애 격리
facet 한 섹션 실패로 전체 검색 UX가 죽지 않게 설계해야 합니다.

### 3) 데이터 품질과 API 품질의 연결
facet은 쿼리 성능만이 아니라 마이그레이션/시드 품질 영향을 직접 받습니다.

## 다음 단계
- facet 결과 캐시/프리컴퓨팅
- 도시별 노출 우선순위 실험
- 사용률 기반 facet 정렬 자동화

Facet 엔진은 검색의 부가기능이 아니라, "사용자가 무엇을 찾게 할지"를 결정하는 핵심 컨트롤 레이어입니다.

