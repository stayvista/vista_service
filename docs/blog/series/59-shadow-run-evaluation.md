---
title: "StayVista 기술 개발기 59: [핵심] Shadow Run - 실험 트래픽으로 모델/프롬프트를 검증하는 안전한 방식"
slug: "59-shadow-run-evaluation"
series: "StayVista 기술 개발기"
order: 59
prev_slug: "58-pii-redaction-pipeline"
next_slug: "60-semantic-cache-singleflight"
status: "publish-ready"
excerpt: "실험 모델을 바로 사용자에게 노출하면 리스크가 큽니다. StayVista는 shadow run을 비동기로 분리해 \"사용자 영향 없이\" 품질 신호를 수집합니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 59: [핵심] Shadow Run - 실험 트래픽으로 모델/프롬프트를 검증하는 안전한 방식

## 한 줄 요약
실험 모델을 바로 사용자에게 노출하면 리스크가 큽니다. StayVista는 shadow run을 비동기로 분리해 "사용자 영향 없이" 품질 신호를 수집합니다.

## Shadow Run 목적
`ChatShadowService`의 목표는 단순 로그 저장이 아닙니다.

- primary 경로와 독립적으로 shadow 추론 수행
- 모델/프롬프트 변경의 품질 신호 수집
- 실패 원인(metrics/error)을 DB에 구조적으로 저장

즉 반영 전 검증과 반영 후 회귀 감지를 동시에 담당합니다.

## 실행 구조
### 1) 비동기 분리
`submit()`은 enable 플래그가 켜진 경우에만 `CompletableFuture.runAsync`로 실행합니다.
primary 응답 지연에 영향을 주지 않기 위한 분립니다.

### 2) 입력/출력 redaction
실행 시작 전에 `PiiRedactor`로 request/response를 마스킹합니다.
shadow 저장 테이블에는 원문 대신 redacted 문자열만 기록합니다.

### 3) shadow 추론
shadow 경로도 routing slot 추출, retrieval, prompt 생성, LLM 호출, strict parse를 동일하게 수행합니다.
다만 실패 시 primary를 건드리지 않고 `route_shadow=ERROR`로 기록 후 종료합니다.

## 저장 스키마
`db/migration/V6__chat_experiment_shadow_prompt_registry.sql` 기준:

- `chat_shadow_run`
- `chat_shadow_sample` (FK: `shadow_run_id`)

`metrics_json`에는 `llm_elapsed_ms`, `retrieval_ms`, `cards_count`, `sources_count`, `used_embedding` 등이 저장됩니다.

## 기술적으로 중요한 포인트
### 1) shadow는 "관측 실험"이지 "대체 실행"이 아닙니다
primary 실패를 shadow로 복구하지 않습니다.
두 경로를 섞기 시작하면 원인 분석이 어려워지고 리스크가 커집니다.

### 2) 모델 선택 우선순위가 명확하다
`stayvista.chat.shadow.model` > fallback model > active model.
개발자는 실험 모델을 코드 수정 없이 바꿀 수 있습니다.

### 3) async 실패도 계측해야 합니다
비동기 경로는 조용히 실패하기 쉽습니다.
`chat_shadow_total{result=async_error|error|success}`는 반드시 회귀 점검 대상입니다.

## 로컬 검증 지표
- `chat_shadow_total{result}`
- shadow 성공률/에러율
- route_primary vs route_shadow 분포
- shadow metrics_json 기반 latency/card/source 분포

## 실전 실험 패턴
### 패턴 1) 신규 모델 검증
- rollout 전 shadow만 활성화
- source_count/card_count/latency 비교
- 기준 충족 시 experiment treatment로 승격

### 패턴 2) 프롬프트 버전 회귀 감시
- 동일 모델에서 promptVersion만 변경
- shadow run의 parse 실패/근거 누락 비율 비교

## 개선 과제
- primary vs shadow 응답 품질 자동 스코어링
- shadow 샘플과 실험 버킷 연결 분석
- shadow executor 동시성/큐 길이 노출

shadow run은 "안전하게 빨리 실험하는 능력"을 만드는 실험 인프라입니다.
