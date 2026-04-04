---
title: "StayVista 기술 개발기 52: [핵심] LLM Budget Controller - p99를 보호하기 위한 Adaptive Degrade"
slug: "52-llm-budget-controller"
series: "StayVista 기술 개발기"
order: 52
prev_slug: "51-llm-execution-gate"
next_slug: "53-llm-model-registry-fallback"
status: "publish-ready"
excerpt: "LLM 품질을 유지하려면 \"언제 포기할지\"를 정해야 합니다. Budget Controller는 p99/거절률 기반으로 LLM 사용량을 동적으로 낮춥니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 52: [핵심] LLM Budget Controller - p99를 보호하기 위한 Adaptive Degrade

## 한 줄 요약
LLM 품질을 유지하려면 "언제 포기할지"를 정해야 합니다. Budget Controller는 p99/거절률 기반으로 LLM 사용량을 동적으로 낮춥니다.

## 왜 필요한가
트래픽 피크에서 LLM 호출을 그대로 유지하면:
- queue reject 증가
- timeout 증가
- 전체 latency 악화

이때 단순 on/off 스위치만 있으면 제어가 거칠어집니다.

## 현재 모델: 3단계 모드
`LlmBudgetController`는 내부 모드를 가집니다.

- NORMAL
- DEGRADED
- SEVERE

모드별 허용 비율:
- NORMAL: 100%
- DEGRADED: `degrade-allow-percent` (기본 45%)
- SEVERE: `severe-allow-percent` (기본 20%)

## 의사결정 방식
### 1) 샘플 수집
요청별로:
- queueWait + llmElapsed (결합 지연)
- rejected 여부
- timeout 여부
를 슬라이딩 윈도우에 적재

### 2) 임계치 판정
- p99 latency
- reject rate
- timeout rate

임계치를 넘으면 모드를 상향, 회복 조건 만족 시 NORMAL 복귀.

### 3) 요청별 허용 판단
메시지 해시 버킷(0~99)으로 샘플링해 허용 비율을 적용합니다.

즉 동일 모드에서도 일부 요청은 LLM, 일부는 템플릿으로 분배됩니다.

## 기술적으로 중요한 포인트
### 1) binary cutoff 대신 확률적 허용
전체 차단보다 품질 저하를 점진적으로 제어합니다.

### 2) 모드 전환 이벤트 계측
`chat_llm_budget_mode_change_total`로 전환 빈도를 추적합니다.

### 3) 라우팅/게이트와의 연동
budget 불허 -> TEMPLATE 경로
게이트 reject -> budget outcome에 반영

### 4) 실험 파라미터화
임계치/허용비율은 config로 노출되어 런타임 튜닝이 가능합니다.

## 지표
- `chat_llm_budget_mode`
- `chat_llm_budget_p99_ms`
- `chat_llm_budget_reject_rate`
- `chat_llm_budget_timeout_rate`
- `chat_llm_budget_decision_total{mode,allowed}`

## 로컬 검증 해석 팁
- SEVERE 모드 장기 유지: 모델/하드웨어 용량 부족 신호
- mode flap(잦은 왕복): 임계치 히스테리시스 부족 가능성
- reject rate는 낮은데 p99만 높음: 모델 지연/프롬프트 과대 가능성

## 개선 과제
- route/intent별 budget 분리
- 사용자 세그먼트별 차등 허용
- budget 모드와 비즈니스 KPI(전환율) 자동 상관 분석

Budget Controller는 "LLM 비용 절감기"가 아니라, 지연/거절률 기준을 지키기 위한 실시간 안정화 장치입니다.
