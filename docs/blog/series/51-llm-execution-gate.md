---
title: "StayVista 기술 개발기 51: [핵심] LLM Execution Gate - 동시성 제한과 대기열 거절을 명시적으로 다루기"
slug: "51-llm-execution-gate"
series: "StayVista 기술 개발기"
order: 51
prev_slug: "50-chat-routing-policy"
next_slug: "52-llm-budget-controller"
status: "publish-ready"
excerpt: "LLM 병목은 피할 수 없습니다. 중요한 건 병목을 숨기지 않고, 시스템이 감당 가능한 만큼만 처리하도록 게이트를 두는 것입니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 51: [핵심] LLM Execution Gate - 동시성 제한과 대기열 거절을 명시적으로 다루기

## 한 줄 요약
LLM 병목은 피할 수 없습니다. 중요한 건 병목을 숨기지 않고, 시스템이 감당 가능한 만큼만 처리하도록 게이트를 두는 것입니다.

## 문제 상황
LLM 호출은 일반 API보다 훨씬 무겁습니다.

- 응답 시간 편차 큼
- 모델/하드웨어 상태 영향 큼
- 동시 요청이 늘면 tail latency 급등

무제한 실행하면 전체 서비스가 느려집니다.

## 게이트 설계
`LlmExecutionGate`는 semaphore 기반입니다.

- `max-concurrency`만큼만 동시 실행 허용
- 초과 요청은 최대 `max-queue-wait-ms` 동안 대기
- 대기 시간 초과 시 reject

반환값(`GateResult`):
- `value`: 실행 결과
- `rejected`: 큐 대기 초과 여부
- `queueWaitMs`: 실제 대기 시간

## 왜 reject를 실패로 노출하나
"조용히 오래 기다리게 하는 것"보다 "빠르게 거절 후 degrade"가 UX와 시스템 모두에 유리합니다.

현재 ChatService는 reject 시:
- `chat_llm_fail_total{reason=queue_rejected}` 기록
- TEMPLATE fallback으로 즉시 강등

## 관측성
게이트는 실행제어와 동시에 관측 포인트를 제공합니다.

- gauge: `llm_inflight`, `llm_queue_depth`
- timer: `llm_queue_wait_ms`
- counter: `llm_reject_rate{reason=queue_timeout}`

이 지표로 "모델이 느린지"와 "큐 정책이 빡센지"를 분리해 해석할 수 있습니다.

## 기술적으로 중요한 포인트
### 1) 공정성(fair semaphore)
기본 `Semaphore(..., true)`로 대기 공정성을 확보합니다.

### 2) 무한 대기 금지
대기 상한이 없으면 요청 누적이 지연 폭발로 전이됩니다.

### 3) 게이트와 라우팅 결합
게이트 거절이 잦으면 라우팅/버짓 정책에서 LLM 비율을 더 줄여야 합니다.

### 4) 게이트는 장애 격리 장치
LLM 병목이 search/booking 같은 핵심 API로 전파되는 것을 막습니다.

## 튜닝 포인트
- `max-concurrency`
- `max-queue-wait-ms`

둘은 트레이드오프입니다.
- 높이면 성공률 증가 가능, 대신 tail latency/자원압박 증가
- 낮추면 빠른 강등 가능, 대신 LLM 활용률 감소

게이트는 LLM 품질 기능이 아니라 안정성 기능입니다. 이 계층 없이 LLM 기능을 적용하면 결국 전체 시스템의 지연/실패 기준을 잃습니다.
