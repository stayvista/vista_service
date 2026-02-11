# B-0457 — Streaming UX: SSE 엔드포인트 + TTFB 최적화

## Goal
LLM이 느린 상황에서도 사용자가 체감하는 응답을 빠르게 만들기 위해 SSE 스트리밍을 도입한다.

## API
- `POST /v1/chat/recommend:stream` (SSE)
  - request: 기존 recommend와 동일
  - response: SSE event stream
    - `event: meta` (route/llm_used)
    - `event: token` (텍스트 조각)
    - `event: done` (최종 JSON payload 또는 요약)

## Metrics
- `chat_ttfb_ms`
- `chat_stream_duration_ms`

## Acceptance Criteria
- 평균 TTFB < 500ms 목표(캐시/RAG 선송출 포함)
- 네트워크 중단 시 cancel 정상 처리
