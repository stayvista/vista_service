---
title: "StayVista 기술 개발기 65: [핵심] Hybrid Ranker + Curation - 벡터/키워드/신선도/수동개입을 결합한 랭킹"
slug: "65-hybrid-ranker-curation"
series: "StayVista 기술 개발기"
order: 65
prev_slug: "64-rag-index-builder-incremental"
next_slug: "66-chat-streaming-sse-contract"
status: "publish-ready"
excerpt: "RAG 랭킹은 단일 점수로는 부족합니다. StayVista는 벡터+lexical RRF에 freshness decay와 curation rule(top pick/blacklist)을 얹어 검증 가능한 랭킹을 만듭니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 65: [핵심] Hybrid Ranker + Curation - 벡터/키워드/신선도/수동개입을 결합한 랭킹

## 한 줄 요약
RAG 랭킹은 단일 점수로는 부족합니다. StayVista는 벡터+lexical RRF에 freshness decay와 curation rule(top pick/blacklist)을 얹어 검증 가능한 랭킹을 만듭니다.

## LocalRagRetriever 랭킹 파이프라인
`searchItems()` 기준 흐름:

1. 후보 문서 로드 (`travel_doc`)
2. curation blacklist 제외
3. lexical rank 계산
4. query embedding + doc vector cosine rank 계산
5. `HybridRanker.fuse(...)`로 RRF 결합
6. freshness decay 적용
7. top pick boost 적용 후 재정렬
8. 상위 hit 반환

## HybridRanker 핵심
`HybridRanker.fuse()`는 문서별 최종 점수를:

- `1 / (rrfK + vectorRank)`
- `1 / (rrfK + lexicalRank)`

합산한 RRF score에 시간 감쇠(`exp(-ageDays/decayWindowDays)`)를 곱해 계산합니다.
decay는 0.35~1.0으로 clamp합니다.

## Curation 개입 레이어
`ChatCurationService.activeRules()`가 활성 룰을 제공합니다.

- `BLACKLIST`: 추천 후보 완전 제외
- `TOP_PICK`: 점수 boost (`weight` 기반)

개발자이 품질 사고 문서를 즉시 배제하거나, 검증된 문서를 상단에 올릴 수 있습니다.

## 기술적으로 중요한 포인트
### 1) 벡터 랭킹 실패를 lexical이 흡수해야 합니다
임베딩 실패/누락 시 vector order가 비어도 lexical order로 결과를 반환합니다.
하나의 신호에 의존하지 않는 것이 개발 안정성의 핵심입니다.

### 2) freshness를 점수에 곱해야 stale 문서가 자연스럽게 내려간다
단순 최신순 정렬은 relevance를 깨고, relevance-only는 오래된 문서를 고착화합니다.
decay 결합이 두 문제를 동시에 완화합니다.

### 3) 수동 개입은 코드 수정 없이 가능해야 합니다
curation rule은 DB+Admin API로 즉시 반영됩니다.
검색 품질 장애 대응 시간이 크게 줄어듭니다.

## 관측성
- `chat_rag_ms`
- `chat_rag_errors_total`
- `chat_curation_applied_total{type=blacklist|top_pick}`
- `chat_rag_index_empty_total`

랭킹 품질은 latency뿐 아니라 blacklist 적용량, top_pick 의존도를 함께 봐야 합니다.

## 실험 리스크
### 리스크 1) top pick 과적용
수동 개입이 과하면 personalization/relevance를 덮어버립니다.

### 리스크 2) blacklist 누적
문서 제거가 누적되면 특정 intent에서 후보가 급감합니다.

### 리스크 3) decay 파라미터 고정
도메인별 신선도 요구가 다른데 동일 decay를 쓰면 편향이 생깁니다.

## 개선 과제
- source_type별 decay 분리
- curation rule TTL/만료 정책
- retrieval 품질 오프라인 평가셋 관리

Hybrid Ranker는 "수학식"보다 "수동 개입이 가능한 랭킹 체계"를 만드는 데 가치가 있습니다.
