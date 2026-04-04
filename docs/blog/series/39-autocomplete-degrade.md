---
title: "StayVista 기술 개발기 39: Unified Autocomplete - OpenSearch 장애를 흡수하는 degrade 설계"
slug: "39-autocomplete-degrade"
series: "StayVista 기술 개발기"
order: 39
prev_slug: "36-search-copilot-filters"
next_slug: "50-chat-routing-policy"
status: "publish-ready"
excerpt: "자동완성은 검색의 입구입니다. 여기서 장애가 나면 사용자는 검색 자체를 시작하지 못합니다. 그래서 성능보다 먼저 가용성을 설계했습니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 39: Unified Autocomplete - OpenSearch 장애를 흡수하는 degrade 설계

## 한 줄 요약
자동완성은 검색의 입구입니다. 여기서 장애가 나면 사용자는 검색 자체를 시작하지 못합니다. 그래서 성능보다 먼저 가용성을 설계했습니다.

## 설계 목표
- 입력 반응성을 유지합니다.
- OpenSearch 장애 시에도 결과를 계속 제공합니다.
- 최근/인기/타입별 후보를 일관된 스키마로 합칩니다.

## 처리 흐름
`AutocompleteService.autocomplete()` 기준:

1. 질의 정규화(NFC, trim, whitespace normalize)
2. 길이 제한/1글자 요청 제한
3. 타입 기본값 적용(CITY/PROPERTY/POI/STATION/AIRPORT)
4. typed query 캐시 조회
5. miss 시 fresh fetch
   - 정상: OpenSearch 우선
   - 예외/비어있음: DB 후보 fallback
6. 결과 비어있으면 recent+popular fallback

## OpenSearch degrade 윈도우
핵심은 "순간 장애를 즉시 우회"하는 로직입니다.

- 연속 실패 카운터 증가
- 연속 실패 3회면 30초 degrade 윈도우 진입
- 윈도우 동안 OpenSearch 호출 생략하고 DB 직행

효과:
- 장애 구간에서 불필요한 외부 호출 폭주를 줄입니다.
- 사용자 체감 지연을 안정화합니다.

## 캐시 계층
- query cache
- popular cache
- recent history(사용자별)

그리고 결과 병합 시 dedupe key 기준 중복 제거를 수행합니다.

## 품질/보호 장치
- 최대 query 길이 제한
- 1글자 query size 상한
- 타입 필터 기본값 제공
- cache hit/source 메트릭 기록

## 지표
- `ac_req_total{cache_hit,source}`
- `ac_latency_ms{source}`
- `ac_reject_total{reason}`
- `ac_empty_query_total`

이 지표 조합으로 "느린가?"보다 "어떤 경로가 실패/우회 중인가?"를 먼저 봅니다.

## 기술적으로 중요한 포인트
### 1) degrade는 fail-open이 아니라 controlled fallback
무조건 성공처럼 보이게 감추는 게 아니라, 소스 전환을 의도적으로 수행합니다.

### 2) 캐시 키 정규화
질의 표현 차이(공백/대소문자)로 캐시가 분산되지 않게 합니다.

### 3) recent/popular는 장애 복구 UX
typed query가 망가져도 사용자가 다음 행동을 할 수 있게 해줍니다.

## 개선 과제
- source별 relevance 점수 비교
- degrade 진입/복귀 이벤트 지표화
- locale/language별 후보 품질 평가 자동화

Autocomplete는 검색의 보조 기능이 아니라, 검색 성공률을 결정하는 첫 번째 품질 지점입니다.
