---
title: "StayVista 기술 개발기 84: [확장] Autocomplete 피드백 루프 - impression/select를 랭킹 신호로 환류하기"
slug: "84-autocomplete-feedback-loop"
series: "StayVista 기술 개발기"
order: 84
prev_slug: "83-my-reservation-inquiry-apis"
next_slug: "85-search-index-sync-outbox-projection"
status: "publish-ready"
excerpt: "자동완성 품질은 검색 알고리즘만으로 고정되지 않았습니다. StayVista는 impression/select 이벤트를 outbox에 적재하고 집계해 `ac_suggest_metric`과 OpenSearch 점수에 반영하는 피드백 루프를 구현했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 84: [확장] Autocomplete 피드백 루프 - impression/select를 랭킹 신호로 환류하기

## 한 줄 요약
입력 추천 품질을 유지하려면 "무엇을 보여줬고 무엇을 선택했는지"를 도메인 이벤트로 남기고, 다시 랭킹 신호로 돌려야 합니다.

## 구현 배경
기존 자동완성은 OpenSearch/DB fallback만으로도 동작했지만, 시간이 지나면 인기 변화가 점수에 반영되지 않는 문제가 있었습니다.

이를 해결하기 위해 다음 경로를 만들었습니다.

1. 사용자 노출/선택 이벤트 수집
2. 이벤트 집계(7일 기준)
3. 랭킹 신호(`ctr_7d`, `popularity_7d`) 갱신
4. 자동완성 검색 점수에 반영

## 1) 이벤트 수집 (`AutocompleteFeedbackService`)
### `recordImpression`
- 입력된 `items`를 `PlaceIdCodec.parseOrNull`로 검증합니다.
- `aggregateType=AUTOCOMPLETE`, `eventType=ac_impression`으로 outbox에 저장합니다.

### `recordSelect`
- `selected` 항목과 노출 목록을 같은 요청에서 저장합니다.
- `ac_select` 이벤트를 outbox에 남기고, 최근 검색어 캐시(`recent`)에도 반영합니다.

핵심 포인트:
- `id(type:canonical_id)`와 `type` 불일치면 즉시 `VALIDATION_ERROR`
- 잘못된 피드백이 집계 파이프라인으로 들어가지 않게 입력 단계에서 차단

## 2) 집계 배치 (`AutocompleteAggregationJob`)
스케줄 배치가 outbox의 `ac_impression`, `ac_select`를 읽어 7일 지표를 계산합니다.

- lookback: `stayvista.autocomplete.aggregate.lookback-hours` (기본 168h)
- scan limit: `stayvista.autocomplete.aggregate.scan-limit`
- 키: `type + canonical_id`

산출값:
- `impressions_7d`
- `selects_7d`
- `ctr_7d = selects_7d / impressions_7d`
- `popularity_7d = impressions_7d`

## 3) DB/검색 인덱스 반영
### DB 반영
`ac_suggest_metric` 테이블(`V9__autocomplete_suggest_metric.sql`)에 upsert합니다.

### OpenSearch 반영
`AutocompleteOpenSearchGateway.updateMetrics`에서 `_bulk` update로 `ctr_7d`, `popularity_7d`를 업데이트합니다.

문서 ID 규칙:
- `city:Seoul`
- `property:1001`
- `poi:30012`

## 4) 검색 점수 결합
자동완성 검색 시 `function_score`에 아래 신호를 함께 반영합니다.

- `weight`
- `ctr_7d`
- `popularity_7d`
- type 가중치(CITY > PROPERTY > POI)

즉 텍스트 매칭 점수에 최근 사용자 행동 신호를 얹는 구조입니다.

## 캐시 무효화 전략
집계 결과가 하나라도 있으면 `invalidateAutocompleteCaches()`를 호출해 query/popular 캐시를 비웁니다. 지표 갱신 후 오래된 정렬이 계속 노출되는 문제를 막기 위한 처리입니다.

## 기술적으로 중요한 포인트
- 피드백 이벤트는 로그가 아니라 도메인 이벤트로 저장해야 재처리/재집계가 가능합니다.
- 노출과 선택을 같은 스키마 축으로 저장해야 CTR 계산이 안정적입니다.
- 랭킹 신호 갱신과 캐시 무효화를 같이 수행해야 반영 지연이 줄어듭니다.

## 남은 과제
- 집계 배치 단위 테스트 추가
- position/score를 활용한 bias 보정(상단 노출 편향 보정)
- `ac_blacklist`와 결합한 자동 제외 규칙 고도화
