---
title: "StayVista 기술 개발기 66: [핵심] Chat Streaming SSE 계약 - meta/token/done/error를 분리한 이유"
slug: "66-chat-streaming-sse-contract"
series: "StayVista 기술 개발기"
order: 66
prev_slug: "65-hybrid-ranker-curation"
next_slug: "67-llm-client-timeout-streaming"
status: "publish-ready"
excerpt: "스트리밍 품질은 토큰 생성 속도보다 \"이벤트 계약이 안정적인가\"에서 결정됩니다. StayVista는 SSE를 `meta/token/done/error` 이벤트로 명확히 분리해 프론트와 백엔드의 책임을 고정했습니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 66: [핵심] Chat Streaming SSE 계약 - meta/token/done/error를 분리한 이유

## 한 줄 요약
스트리밍 품질은 토큰 생성 속도보다 "이벤트 계약이 안정적인가"에서 결정됩니다. StayVista는 SSE를 `meta/token/done/error` 이벤트로 명확히 분리해 프론트와 백엔드의 책임을 고정했습니다.

## 엔드포인트와 이벤트 모델
`ChatController`는 `/v1/chat/recommend:stream`에서 `SseEmitter`를 반환합니다.

전송 이벤트:
- `meta`: route/사유/llm_used 같은 제어 정보
- `token`: 증분 텍스트 (`{"text": "..."}`)
- `done`: 최종 `ChatRecommendData` 전체 payload
- `error`: 스트림 중 예외 정보

이 분리 덕분에 UI는 토큰 렌더링과 상태 관리(meta/done)를 독립적으로 처리할 수 있습니다.

## 스트리밍 실행 흐름
### 1) 컨트롤러 계층
- `SseEmitter(0L)`로 타임아웃을 무제한 설정
- `AtomicBoolean cancelled`로 연결 종료/타임아웃 상태 추적
- 별도 daemon thread에서 서비스 실행

### 2) 서비스 계층
`ChatService.recommendStream()`은 sync 경로와 동일한 라우팅/캐시/검증을 수행하되:
- `onMeta` 콜백으로 초기 상태 전송
- `onToken` 콜백으로 청크 전송
- 최종적으로 `done` 이벤트에 완성 응답 전달

즉 stream/non-stream 간 비즈니스 로직 일관성을 유지합니다.

## 기술적으로 중요한 포인트
### 1) meta를 먼저 보내야 클라이언트 상태가 안정된다
route가 `LLM`인지 `TEMPLATE`인지 초기에 알려주면 스피너/UX 분기를 빠르게 결정할 수 있습니다.

### 2) done payload는 "전체 정답"이어야 한다
token 누락/재정렬이 있어도 `done`의 정합성으로 최종 화면을 보정할 수 있습니다.
증분 토큰만 신뢰하면 네트워크 환경에서 쉽게 깨집니다.

### 3) cancel은 예외가 아니라 정상 경로다
클라이언트 이탈 시 `CancellationException`으로 조용히 종료합니다.
분석 노이즈를 줄이는 중요한 설계입니다.

## 측정 지표
스트리밍 경로는 최소 아래 지표를 함께 봅니다.

- `chat_ttfb_ms`: 첫 이벤트까지 시간
- `chat_stream_duration_ms`: 스트림 전체 지속 시간
- `chat_latency_seconds`: 최종 응답 기준 지연
- `llm_stream_cancel_total`: LLM 스트림 중단 횟수

핵심은 평균 latency가 아니라 TTFB 분포입니다. 체감 속도는 첫 토큰 시점에서 결정됩니다.

## 오류 처리 전략
컨트롤러에서 예외 발생 시:
- 가능하면 `error` 이벤트 전송
- 이후 `completeWithError`로 종료

서비스 측에서는 parse/timeout/queue reject 실패를 템플릿 fallback으로 흡수하므로, stream도 sync와 같은 최소 품질 하한선을 가집니다.

## 개선 과제
- SSE event id/retry 정책 명시
- 토큰 flush 주기와 청크 크기 튜닝
- client reconnect 시 done 재수신 전략

스트리밍은 "토큰을 흘리는 기능"이 아니라 "부분 실패에서도 일관된 최종 응답을 보장하는 프로토콜"입니다.
