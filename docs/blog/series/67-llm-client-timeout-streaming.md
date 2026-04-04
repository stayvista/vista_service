---
title: "StayVista 기술 개발기 67: [핵심] LLM Client 안정성 설계 - Soft/Hard Timeout, Streaming Cancel, 에러 분류"
slug: "67-llm-client-timeout-streaming"
series: "StayVista 기술 개발기"
order: 67
prev_slug: "66-chat-streaming-sse-contract"
next_slug: "68-widget-session-snapshot-hardening"
status: "publish-ready"
excerpt: "LLM 품질만 높아도 서비스는 안정해지지 않습니다. StayVista는 `LocalLlmClient`에서 timeout/cancel/에러 분류를 분리해 장애를 예측 가능한 형태로 제어합니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 67: [핵심] LLM Client 안정성 설계 - Soft/Hard Timeout, Streaming Cancel, 에러 분류

## 한 줄 요약
LLM 품질만 높아도 서비스는 안정해지지 않습니다. StayVista는 `LocalLlmClient`에서 timeout/cancel/에러 분류를 분리해 장애를 예측 가능한 형태로 제어합니다.

## 왜 LLM 클라이언트 계층이 중요한가요
LLM 호출은 일반 DB 조회와 다르게 다음 리스크가 큽니다.

- 지연 편차가 큽니다.
- 스트리밍 중 클라이언트가 자주 이탈합니다.
- 네트워크/모델 상태에 따라 실패 유형이 다릅니다.

이 리스크를 서비스 코드 곳곳에서 처리하면 정책 일관성이 깨집니다. 그래서 LLM 호출 정책을 클라이언트 계층으로 모았습니다.

## LocalLlmClient의 실행 모델
### 1) 동기 호출(`generate`)
- `CompletableFuture`로 호출을 감쌉니다.
- `soft-timeout-ms`(기본 30초) 초과 시 즉시 취소하고 `LlmSoftTimeoutException`을 반환합니다.
- HTTP 요청 자체는 `hard-timeout-ms`(기본 60초)로 제한합니다.

### 2) 스트리밍 호출(`generateStream`)
- `streaming-enabled=true`면 `/api/generate`를 stream 모드로 호출합니다.
- `cancelSignal()`이 true가 되면 `CancellationException`으로 중단합니다.
- stream 비활성화 시 non-stream 호출로 자동 degrade합니다.

### 3) 출력 계약
- non-stream은 `format=json`으로 고정합니다.
- 빈 응답은 성공으로 보지 않고 `empty_response` 오류로 처리합니다.

## 기술적으로 중요한 포인트
### 1) soft timeout과 hard timeout을 분리해야 합니다
soft timeout은 사용자 경험 보호용이고, hard timeout은 리소스 보호용입니다.
두 타임아웃을 분리하지 않으면 빠른 fallback과 연결 정리를 동시에 만족시키기 어렵습니다.

### 2) cancel은 실패가 아니라 정상 제어 흐름입니다
스트리밍 UI에서는 탭 이동/스크롤 전환으로 취소가 자주 발생합니다.
이를 오류처럼 다루면 검증 지표가 오염되고 장애 탐지가 늦어집니다.

### 3) 오류를 타입별로 분류해야 대응이 빨라집니다
`connect`, `soft_timeout`, `hard_timeout`, `http_xxx`, `empty_response`, `stream_connect`를 구분하면
문제가 네트워크인지 모델인지 라우팅인지 즉시 판단할 수 있습니다.

## 핵심 메트릭
- `llm_ms`
- `llm_errors_total{reason}`
- `llm_timeout_count{type=soft|hard}`
- `llm_error_count{reason}`
- `llm_stream_cancel_total`

중요한 것은 에러 총량보다 `reason` 분포 변화입니다.

## 장애 시나리오별 대응
### 시나리오 1) soft timeout 급증
- 증상: `soft_timeout` 급증, fallback 경로 증가
- 대응: 라우팅에서 LLM 비율 축소, budget 모드 조정

### 시나리오 2) connect 에러 급증
- 증상: `connect`/`stream_connect` 상승
- 대응: LLM 서버 헬스 점검, probe 기반 트래픽 차단

### 시나리오 3) empty_response 증가
- 증상: 모델 응답 본문 비어 있음
- 대응: 모델 버전 확인, prompt/토큰 제한 재검토

## 개선 과제
- 모델별 latency/오류 지표 분리
- 스트리밍 chunk 품질 지표 추가
- timeout 값 자동 튜닝 정책 도입

LLM 클라이언트는 단순 HTTP 래퍼가 아니라, 서비스 안정성을 지키는 안정성 경계로 다루셔야 합니다.
