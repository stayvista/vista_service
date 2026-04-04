---
title: "StayVista 기술 개발기 31: OpenSearch 우선 + DB Fallback - 검색 신뢰성을 올리는 이중 경로 설계"
slug: "31-opensearch-fallback"
series: "StayVista 기술 개발기"
order: 31
prev_slug: "30-search-v2-engine"
next_slug: "33-facet-engine-server-driven"
status: "publish-ready"
excerpt: "검색 품질은 OpenSearch가 담당하고, 서비스 가용성은 DB fallback이 책임집니다. 둘 중 하나만으로는 개발 안정성을 확보하기 어렵습니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 31: OpenSearch 우선 + DB Fallback - 검색 신뢰성을 올리는 이중 경로 설계

## 한 줄 요약
검색 품질은 OpenSearch가 담당하고, 서비스 가용성은 DB fallback이 책임집니다. 둘 중 하나만으로는 개발 안정성을 확보하기 어렵습니다.

## 왜 이중 경로가 필요한가
OpenSearch는 빠르고 강력하지만 실험 리스크가 있습니다.

- 인덱스 지연
- 매핑 문제
- 노드 장애
- 일시적 응답 오류

이때 검색 API 전체가 죽으면 안 됩니다.

## 현재 경로 선택 로직
`SearchService.search()`에서:

1. `canUseOpenSearch(request)`로 경량 조건인지 판별
2. 가능하면 OpenSearch 조회 시도
3. 결과가 비었는데 DB에 결과가 있으면 `db_fallback`
4. OpenSearch 예외 시 `db_error_fallback`
5. 불가능한 요청(복잡 필터 포함)은 바로 DB 경로

즉 OpenSearch는 "우선 경로"이지 "단일 경로"가 아닙니다.

## OpenSearchClient 핵심 구현
- 인덱스/alias 자동 보장(`ensureIndexAndAlias`)
- sort + `search_after` 커서 기반 페이지네이션
- 요청 필터를 bool query로 구성
- 실패 시 예외 상위 전파(서비스 레이어 fallback 트리거)

## 중요 포인트: 빈 결과 fallback
로컬 검증에서 더 위험한 건 "에러"보다 "조용한 빈 결과"입니다.
현재 구현은 OpenSearch 결과가 empty일 때 DB를 재조회해 실제 빈 결과인지 확인합니다.

이 설계로 인덱스 지연/누락 상황에서 사용자에게 빈 화면이 노출되는 확률을 줄입니다.

## 관측 지표
- `search_opensearch_empty_fallback_total`
- `search_opensearch_errors_total`
- `search_latency_ms{source=opensearch|db|db_fallback|db_error_fallback}`

source 태그를 분리해 봐야 원인을 분해할 수 있습니다.

## 기술적으로 중요한 트레이드오프
### 장점
- 검색 API 가용성 강화
- 인덱스 문제를 즉시 장애로 확장하지 않음

### 비용
- fallback 시 DB 부하 증가
- 경로별 결과 차이(랭킹/점수)의 일관성 이슈

## 개선 방향
- fallback 비율 회귀 기준
- 경로 간 결과 diff 샘플링
- 인덱스 freshness 지표와 fallback 상관 분석

검색 시스템에서 "정답률"과 "가용성"은 별개 목표입니다.
이중 경로는 그 둘을 동시에 지키기 위한 실용적 타협입니다.
