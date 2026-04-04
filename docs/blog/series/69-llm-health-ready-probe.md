---
title: "StayVista 기술 개발기 69: [핵심] LLM Health/Ready Probe - \"살아있음\"과 \"준비됨\"을 분리해서 보는 이유"
slug: "69-llm-health-ready-probe"
series: "StayVista 기술 개발기"
order: 69
prev_slug: "68-widget-session-snapshot-hardening"
next_slug: "70-observability-slo"
status: "publish-ready"
excerpt: "LLM 서버가 떠 있어도 모델이 준비되지 않으면 서비스는 정상 동작하지 않습니다. StayVista는 `/healthz`와 `/readyz`를 분리해 로컬 라우팅 판단을 명확하게 만듭니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 69: [핵심] LLM Health/Ready Probe - "살아있음"과 "준비됨"을 분리해서 보는 이유

## 한 줄 요약
LLM 서버가 떠 있어도 모델이 준비되지 않으면 서비스는 정상 동작하지 않습니다. StayVista는 `/healthz`와 `/readyz`를 분리해 로컬 라우팅 판단을 명확하게 만듭니다.

## 왜 Probe를 분리하나요
로컬 실험에서 자주 발생하는 오해는 다음과 같습니다.

- 프로세스는 살아있습니다.
- 하지만 필요한 모델은 아직 pull되지 않았습니다.
- 결과적으로 실제 요청은 실패합니다.

이 문제를 막기 위해 `LlmProbeService`는 상태를 두 단계로 구분합니다.

## 구현 구조
### 1) healthz
`/internal/llm/healthz`는 `probeTags()`만 수행합니다.

- 대상: `${baseUrl}/api/tags`
- 의미: 엔드포인트 연결 가능 여부
- 결과: `up` 또는 `down`

즉 "서버가 응답하는지"만 봅니다.

### 2) readyz
`/internal/llm/readyz`는 두 단계를 모두 확인합니다.

1. tags 엔드포인트 접근 가능
2. `modelRegistry.activeModel()`이 tags 목록에 실제 존재

모델이 없으면 `not_ready`로 반환합니다.

## 컨트롤러 응답 계약
`LlmProbeController`는 결과에 따라 HTTP 상태를 다르게 반환합니다.

- `ok=true` -> `200 OK`
- `ok=false` -> `503 Service Unavailable`

응답 본문:
- `service`
- `status`
- `ok`
- `detail`

이 계약 덕분에 로컬 라우터나 테스트 게이트에서 동일한 기준으로 상태를 판정할 수 있습니다.

## 기술적으로 중요한 포인트
### 1) health와 readiness를 섞으면 라우팅 오판이 발생합니다
health만 통과시키면 모델 미준비 인스턴스에도 트래픽이 유입됩니다.
ready 검증은 반드시 모델 존재까지 포함하셔야 합니다.

### 2) active model 기준 검증이 핵심입니다
시스템이 실제로 쓰는 모델(`activeModel`)이 준비됐는지 확인해야 의미가 있습니다.
임의 모델 확인은 상태 판단에 도움이 되지 않습니다.

### 3) detail 메시지는 개발자 친화적으로 유지해야 합니다
`model 'x' is not pulled`, `HTTP 503` 같은 구체 메시지가 있어야 즉시 조치가 가능합니다.

## 권장 로컬 검증 방식
### 로컬 실행 시
- `healthz`는 생존 확인
- `readyz`가 OK 될 때까지 트래픽 차단

### 장애 시
- `down`이면 네트워크/프로세스부터 점검
- `not_ready`면 모델 pull/registry 설정부터 점검

## 개선 과제
- probe 결과를 메트릭으로 노출
- 모델별 readiness 상세 상태 확장
- probe 실패 회귀 판정 기준 표준화

LLM 실험에서는 "프로세스 생존"보다 "요청 처리 준비 상태"가 더 중요합니다. probe를 분리하면 이 차이를 개발 단계에서 명확히 다룰 수 있습니다.
