---
title: "StayVista 기술 개발기 50: [핵심] Chat Routing Policy - TEMPLATE/LLM/CLARIFY를 나누는 기준"
slug: "50-chat-routing-policy"
series: "StayVista 기술 개발기"
order: 50
prev_slug: "39-autocomplete-degrade"
next_slug: "51-llm-execution-gate"
status: "publish-ready"
excerpt: "LLM을 \"항상 호출\"하면 비용과 지연이 폭발하고, \"항상 규칙\"이면 품질이 떨어집니다. 라우팅 정책은 이 균형을 제어하는 핵심 로직입니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 50: [핵심] Chat Routing Policy - TEMPLATE/LLM/CLARIFY를 나누는 기준

## 한 줄 요약
LLM을 "항상 호출"하면 비용과 지연이 폭발하고, "항상 규칙"이면 품질이 떨어집니다. 라우팅 정책은 이 균형을 제어하는 핵심 로직입니다.

## 라우팅이 필요한 이유
대화 요청은 성격이 다릅니다.

- 단순 질의: 규칙/템플릿으로 충분
- 정보 부족: 먼저 보완질문이 필요
- 복합 요청: LLM 생성이 유리

이 세 종류를 구분하지 않으면, 성능/비용/품질이 동시에 악화됩니다.

## 현재 라우팅 구성
`ChatRoutingPolicy.decide()`는 다음 입력을 사용합니다.

- message
- 슬롯 추출 결과(`city`, `days`, `budget`, `companions`, `intent`, `sourceTypes`)
- RAG hit 결과
- LLM 사용 가능 여부(`llmAllowed`)

출력:
- `ASK_CLARIFICATION`
- `TEMPLATE`
- `LLM`

## 핵심 분기 규칙
### 1) 도시 미확정이면 CLARIFICATION
핵심 슬롯이 비어 있으면 바로 추천하지 않습니다.

### 2) RAG hit 부족이면 CLARIFICATION
근거가 약한 상태에서 생성 답변을 밀어붙이지 않습니다.

### 3) LLM 키워드/문장 길이 기반 LLM 후보
"일정/동선/비교/설명" 성격 또는 긴 자연어 요청이면 LLM 경로를 고려합니다.

### 4) LLM 불가 시 TEMPLATE degrade
LLM이 꺼져 있거나 budget 정책상 불허면 TEMPLATE로 강등합니다.

## 슬롯 추출의 역할
`extractSlots()`는 message + context에서:
- city
- days
- budget
- companions
- intent
- source type
를 추출합니다.

이 슬롯 품질이 라우팅 정확도를 직접 결정합니다.

## 기술적으로 중요한 포인트
### 1) 라우팅은 성능 정책이다
경로 선택이 곧 p95/p99를 결정합니다.

### 2) 라우팅은 품질 정책이다
근거가 없을 때 질문을 먼저 하도록 강제해 환각/노이즈를 줄입니다.

### 3) 라우팅은 비용 정책이다
LLM 사용률(`llm_used_rate`)을 제어하는 1차 게이트입니다.

### 4) 라우팅 근거를 남겨야 추적 가능합니다
`reason` 필드를 남겨야 왜 특정 경로가 선택됐는지 디버깅할 수 있습니다.

## 로컬 검증 지표
- `chat_route_total{route=*}`
- `llm_used_rate`
- clarify 관련 handoff 지표
- fallback 비율

라우팅 회귀는 latency보다 먼저 "경로 비율 분포"에서 나타나는 경우가 많습니다.

## 개선 과제
- 룰 기반 분기에 통계/학습 신호 결합
- 라우팅 결정의 오프라인 평가셋 관리
- intent/sourceType 추출 회귀 자동 탐지

라우팅 정책은 LLM 시스템에서 프롬프트만큼 중요합니다. "어떤 모델을 쓰나"보다 먼저 "언제 모델을 쓰나"를 결정하기 때문입니다.

