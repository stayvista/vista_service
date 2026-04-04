---
title: "StayVista 기술 개발기 62: [핵심] AI Copilot Orchestrator - 검색/가격/상세/재고 툴체인을 조합하는 방식"
slug: "62-copilot-orchestrator"
series: "StayVista 기술 개발기"
order: 62
prev_slug: "61-prompt-registry-experiment-rollout"
next_slug: "63-handoff-advisor"
status: "publish-ready"
excerpt: "Copilot의 핵심은 모델 답변 자체보다 \"어떤 도구를 어떤 순서로 호출해 근거를 만들었는가\"입니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 62: [핵심] AI Copilot Orchestrator - 검색/가격/상세/재고 툴체인을 조합하는 방식

## 한 줄 요약
Copilot의 핵심은 모델 답변 자체보다 "어떤 도구를 어떤 순서로 호출해 근거를 만들었는가"입니다.

## 오케스트레이터 목표
`/v1/chat/copilot/orchestrate`는 한 번의 요청에서:
- 검색 결과
- 가격 캘린더
- 숙소 상세
- 재고 가능성
을 조합해 "행동 가능한 추천"을 만듭니다.

## 실행 흐름
`ChatCopilotOrchestratorService.orchestrate()` 기준:

1. 세션 상태 정규화
2. `search_properties` 호출
3. 가능하면 `get_price_calendar` 호출
4. 검색 상위 숙소 기준 `get_property_detail` 호출
5. 일정이 있으면 `check_availability` 호출
6. evidence/action/confidence 조합 응답 생성
7. safety policy 적용
8. 메트릭 기록 및 tool trace 반환

## tool trace의 의미
각 도구는 `success/failed/skipped` 상태와 상세를 남깁니다.
이 trace가 있어야:
- 왜 degraded 되었는지
- 어떤 tool이 병목인지
- 어떤 컨텍스트가 빠졌는지
를 로컬 계측 결과에서 즉시 확인할 수 있습니다.

## degraded 응답 설계
조건:
- 검색 결과 없음
- tool 실패 발생

동작:
- fallback answer 반환
- `retry_with_patch` 액션 제공
- confidence 하향

중요한 점은 "실패를 숨기지 않고, 다음 행동을 제안"하는 것입니다.

## evidence 중심 응답
응답에는 추천 근거가 함께 포함됩니다.

- source_type/source_id/title/value
- why_recommended
- cautions

이 구조는 설명 가능성과 디버깅 가능성을 동시에 높입니다.

## 기술적으로 중요한 포인트
### 1) 순차 호출 + 조건부 skip
불필요한 도구를 호출하지 않아 latency를 절약합니다.

### 2) 도구 실패 격리
일부 실패가 전체 요청 실패로 번지지 않게 degraded 경로를 둡니다.

### 3) action payload 표준화
`apply_filters`, `open_property`, `retry_with_patch` 형식을 고정해 프론트 연계를 안정화합니다.

### 4) 성능/품질 메트릭 동시 기록
- latency (`chat_copilot_orchestrator_latency_ms`)
- degraded 비율
- no result 비율
- confidence 분포

## 로컬 검증 관점
오케스트레이터 품질은 단일 정확도 점수보다 다음 조합으로 봐야 합니다.
- degraded 비율
- action apply 성공률
- handoff 후 검색 전환율

## 남은 과제
- tool 병렬화 실험(현재는 순차 중심)
- session state 보강 자동화
- 실패 원인별 리커버리 액션 다양화

Copilot은 "답변 생성기"가 아니라 "도구 조합 실행기"입니다. 신뢰성은 모델보다 오케스트레이션 품질에서 먼저 결정됩니다.
