---
title: "StayVista 기술 개발기 60: Semantic Cache + Singleflight - LLM 호출 중복을 줄이는 캐시 계층"
slug: "60-semantic-cache-singleflight"
series: "StayVista 기술 개발기"
order: 60
prev_slug: "59-shadow-run-evaluation"
next_slug: "61-prompt-registry-experiment-rollout"
status: "publish-ready"
excerpt: "LLM 최적화의 핵심은 모델 튜닝보다 \"같은 계산을 다시 하지 않는 것\"입니다. StayVista는 prompt/retrieval/semantic/singleflight를 겹쳐서 중복을 줄입니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 60: Semantic Cache + Singleflight - LLM 호출 중복을 줄이는 캐시 계층

## 한 줄 요약
LLM 최적화의 핵심은 모델 튜닝보다 "같은 계산을 다시 하지 않는 것"입니다. StayVista는 prompt/retrieval/semantic/singleflight를 겹쳐서 중복을 줄입니다.

## 캐시 계층 구성
### 1) Retrieval Cache
- 키: retrieval query hash
- 값: RAG 검색 결과
- 목적: 동일 질의의 벡터/검색 반복 제거

### 2) Prompt Cache
- 키: prompt 조합 키
- 값: ChatRecommendData
- 목적: 동일 컨텍스트 응답 재사용

### 3) Semantic Cache
- 질의 임베딩 코사인 유사도 기반
- threshold 이상이면 근사 질의 결과 재사용
- namespace로 범위를 분리

### 4) Vector Cache
- 임베딩 결과 자체 캐시
- 동일 문장 embed 재계산 방지

## singleflight의 역할
`ChatCacheService.singleFlight`는 같은 키에 대한 동시 미스 요청을 leader/join 방식으로 묶습니다.

- leader 1개만 실제 계산 실행
- 나머지는 leader 결과를 join

이 메커니즘이 없으면 캐시 미스 폭주 시 오히려 하류 부하가 급증합니다.

## Semantic Cache 동작
`SemanticCacheService.lookup()`는:
- query 임베딩 획득
- namespace 내 엔트리와 코사인 유사도 계산
- threshold 이상 최고 유사도 항목 hit
- hit 시 response에 cache 힌트 컨텍스트 추가

put 시에는:
- max entries 상한 유지
- TTL 기반 만료
- 오래된 항목 제거

## 관측성
- `chat_prompt_cache_total{hit|miss}`
- `chat_retrieval_cache_total{hit|miss}`
- `chat_semantic_cache_total{hit|miss|put|embed_error}`
- `chat_singleflight_total{scope,result}`

cache hit율과 LLM 사용률(`llm_used_rate`)을 함께 봐야 최적화 효과를 해석할 수 있습니다.

## 기술적으로 중요한 포인트
### 1) 캐시 적중률보다 "폭주 완화"가 먼저
singleflight는 hit율과 무관하게 폭주 시 필수입니다.

### 2) semantic threshold 튜닝
너무 높으면 miss 과다, 너무 낮으면 품질 저하.

### 3) namespace 분리
서로 다른 컨텍스트(도시/모델/루트)가 섞이면 부정확한 재사용이 발생합니다.

### 4) 캐시 실패는 안전하게 무시
Redis 오류/embed 오류가 서비스 전체 실패로 전이되지 않게 설계합니다.

## 개선 과제
- semantic cache 품질 평가셋 관리
- cache hit 경로와 사용자 만족도 상관 분석
- TTL 동적 조정(트래픽/품질 기반)

결론적으로 캐시는 성능 기능이면서 품질 기능입니다. 특히 LLM 시스템에서는 "얼마나 잘 재사용하느냐"가 비용과 지연 기준을 동시에 좌우합니다.
