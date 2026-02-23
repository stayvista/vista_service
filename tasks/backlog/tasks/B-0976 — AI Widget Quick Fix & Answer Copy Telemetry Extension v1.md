# B-0976 — AI Widget Quick Fix & Answer Copy Telemetry Extension v1

## Goal
AI 위젯의 `빠른 보완` 및 `요약 복사` 행동을 서버 표준 이벤트로 수집해, 보완 UX 품질과 답변 재사용도를 계량한다.

## Scope
- telemetry 허용 이벤트 추가
  - `ai_widget_quick_fix_click`
  - `ai_widget_answer_copy_click`
- 검증 규칙 추가
  - `ai_widget_quick_fix_click` 이벤트는 `clarify_slot` 필수
- 메트릭 수집
  - `ai_widget_quick_fix_slot_total{slot}`
  - `ai_widget_quick_fix_scope_total{scope}`
  - `ai_widget_answer_copy_scope_total{scope}`
- source allowlist 확장
  - `quick_fix`

## Acceptance Criteria
- 신규 이벤트가 `/v1/telemetry/events`에 정상 수집된다
- `clarify_slot` 누락 quick-fix 이벤트는 검증 오류로 차단된다
- 단위 테스트에서 신규 이벤트/메트릭 수집이 검증된다
