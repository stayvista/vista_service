# B-0988 — AI Widget Search Blocked Reason Telemetry Extension v1

## Goal
AI 위젯에서 검색/결과 이동이 차단되는 원인을 이벤트 수준에서 분리 수집해 UX 병목을 정확히 진단한다.

## Scope
- 신규 payload 확장
  - `ai_widget_search_blocked` 이벤트에 `block_reason` 필수
  - 허용값: `missing_slots|context_drift`
- 서버 검증 규칙 추가
  - `ai_widget_search_blocked`에서 누락/비허용값 요청 차단
- 메트릭 확장
  - `ai_widget_search_block_reason_total{reason}`
  - `ai_widget_search_block_scope_total{reason,scope}`

## Acceptance Criteria
- 차단 이벤트 수집 시 reason 태그가 분리 집계된다
- `block_reason` 누락/오입력 요청은 `VALIDATION_ERROR`로 차단된다
- 단위 테스트에 신규 검증/메트릭 케이스가 추가되고 통과한다
