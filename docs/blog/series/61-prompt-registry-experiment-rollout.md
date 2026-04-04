---
title: "StayVista 기술 개발기 61: [핵심] Prompt Registry + Experiment Rollout - 프롬프트를 버전 관리 가능한 자산으로 관리하기"
slug: "61-prompt-registry-experiment-rollout"
series: "StayVista 기술 개발기"
order: 61
prev_slug: "60-semantic-cache-singleflight"
next_slug: "62-copilot-orchestrator"
status: "publish-ready"
excerpt: "프롬프트를 코드 문자열로 두면 실험과 롤백이 느립니다. StayVista는 `chat_prompt_template` + `chat_experiment`를 분리해 버전/활성화/롤아웃을 API로 제어합니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 61: [핵심] Prompt Registry + Experiment Rollout - 프롬프트를 버전 관리 가능한 자산으로 관리하기

## 한 줄 요약
프롬프트를 코드 문자열로 두면 실험과 롤백이 느립니다. StayVista는 `chat_prompt_template` + `chat_experiment`를 분리해 버전/활성화/롤아웃을 API로 제어합니다.

## 왜 프롬프트 레지스트리가 필요한가
LLM 품질 이슈 대부분은 모델보다 프롬프트에서 발생합니다. 그런데 프롬프트가 코드 내부에 있으면:

- hotfix에 코드 수정/재실행 필요
- 버전 추적/비교 어려움
- 롤백 시간이 길어짐

`PromptRegistryService`는 이를 DB 기반 아티팩트 관리로 바꿉니다.

## Prompt Registry 구조
테이블: `chat_prompt_template`

- 키: `(prompt_key, version)` unique
- 데이터: `system_prompt`, `user_prompt_template`
- 상태: `is_active`

관리 API:
- `GET /v1/admin/chat/prompts`
- `POST /v1/admin/chat/prompts` (upsert + optional activate)
- `POST /v1/admin/chat/prompts/rollback`

`activate=true`면 같은 `prompt_key`의 기존 active를 먼저 0으로 내린 뒤 목표 버전을 active로 만듭니다.

## Experiment Rollout 구조
테이블: `chat_experiment` (`experiment_key=chat-core`)

- `enabled`
- `rollout_percent` (0~100)
- `treatment_model`
- `prompt_version`
- `parameters_json`

관리 API:
- `GET /v1/admin/chat/experiments/chat-core`
- `POST /v1/admin/chat/experiments/chat-core`

`ChatExperimentService.assign()`는 subject hash 기반으로 `CONTROL`/`TREATMENT`를 안정 분배합니다.

## ChatService에서의 결합 방식
요청 처리 시:

1. `chatExperimentService.assign(...)`
2. bucket이 `TREATMENT`이면 `model_override`, `prompt_version` 적용
3. prompt factory가 해당 version 템플릿 사용
4. 결과 context에 `experiment_bucket`, `experiment_prompt_version` 기록

즉 실험 변수(모델/프롬프트)는 실행 직전에 주입되고, 응답에는 추적 가능성이 남습니다.

## 기술적으로 중요한 포인트
### 1) 버전 선택과 활성화는 별개다
버전을 미리 넣어두고 필요 시 activate/rollout만 조정합니다.
이 분리가 있어야 코드 반영 타이밍과 실험 타이밍을 분리할 수 있습니다.

### 2) bucket 할당은 안정적이어야 한다
subject hash `% 100` 방식은 세션 간 흔들림이 적고 재현이 쉽습니다.
실험 노이즈를 줄이는 데 중요합니다.

### 3) config cache는 짧게, 하지만 즉시성도 보장
`ChatExperimentService`는 1초 캐시를 둡니다.
DB 과부하는 줄이면서 설정 반영 지연을 최소화합니다.

## 로컬 검증 지표
- `chat_experiment_assignment_total{bucket}`
- `chat_experiment_latency_ms{bucket}`
- `chat_experiment_zero_result_total{bucket}`
- `chat_experiment_fallback_total{bucket}`
- `chat_prompt_registry_total{action}`
- `chat_experiment_config_total{action}`

실험 판단은 평균 품질이 아니라 `fallback/zero-result/latency`를 함께 봐야 합니다.

## 롤아웃/롤백 로컬 재현 시나리오
### 롤아웃
1. prompt v2 upsert (비활성)
2. shadow로 안전성 확인
3. experiment rollout 5% -> 20% -> 50%
4. 지표 안정 시 active 전환

### 롤백
1. rollout_percent 즉시 0
2. prompt rollback API로 이전 버전 active
3. fallback/parse fail 정상화 확인

## 개선 과제
- experiment parameters_json 스키마 계약화
- 버전별 품질 리포트 자동 생성
- prompt lint(금칙어/정책 위반) 자동 검사

프롬프트를 "코드 주석 같은 문자열"이 아니라 "검증 가능한 버전 자산"으로 다루면, 실험 속도와 안전성을 동시에 올릴 수 있습니다.
