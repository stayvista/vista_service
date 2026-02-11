# B-0455 — LLM Concurrency Control: 세마포어 + 짧은 큐 + Degrade

## Goal
LLM 호출 폭주 시에도 tail latency(p99)를 보호한다. 세마포어로 동시 실행을 제한하고, 큐 대기 시간이 길어지면 즉시 템플릿으로 degrade 한다.

## Design
- `LlmExecutionGate`
  - inflight semaphore: `llm.max_inflight` (예: 2~4)
  - queue wait timeout: `llm.max_queue_wait_ms` (예: 300ms)
- 초과 요청:
  - queue wait <= timeout: 대기 후 실행
  - timeout 초과: `RejectedByGate` → 템플릿 fallback

## Metrics
- `llm_inflight`
- `llm_queue_depth`
- `llm_queue_wait_ms`
- `llm_reject_rate`

## Tests
- 동시 요청 N개(예: 20) 시 reject/fallback 비율 확인
- queue wait timeout 동작 확인

## Acceptance Criteria
- 폭주 상황에서도 API 스레드 고갈/메모리 폭증 없음
- p99가 hard timeout에 붙기 전에 reject+fallback으로 보호됨
