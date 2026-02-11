# B-0452 — Kotlin LLM Client (Ollama): Timeout/Streaming/Cancel

## Goal
Spring Boot Kotlin에서 로컬 Ollama 호출을 표준화(단일 클라이언트)하고, p99 보호를 위해 timeout/streaming/cancel을 지원한다.

## Scope
- `LocalLlmClient` 구현
- 동기 응답 + SSE/streaming 지원
- timeout 정책(soft/hard) + 실패 시 degrade 전략

## Public API (Internal)
- `LLMClient.generate(req): LLMResponse`
- `LLMClient.generateStream(req): Flow<String>` (SSE)

## Config
- `LLM_BASE_URL`
- `LLM_MODEL_CHAT`
- `LLM_TIMEOUT_SOFT_MS` (예: 1800)
- `LLM_TIMEOUT_HARD_MS` (예: 5000)
- `LLM_STREAMING_ENABLED=true|false`

## Error Handling
- 연결 실패/empty response/timeout 시:
  - 메트릭 증가
  - 상위 오케스트레이터에 "LLM 실패"로 전달 → 템플릿 fallback

## Observability
- metrics: `llm_ms`, `llm_timeout_count`, `llm_error_count`
- tracing span: `llm.generate`

## Tests
- MockWebServer로 Ollama API mocking
- timeout 강제 테스트
- streaming cancel 테스트(클라이언트 disconnect 시)

## Acceptance Criteria
- LLM 서버 down이어도 chat API는 정상(템플릿) 응답
- streaming 모드에서 TTFB 측정 가능(메트릭)
