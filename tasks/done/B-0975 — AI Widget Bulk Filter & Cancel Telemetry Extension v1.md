# B-0975 — AI Widget Bulk Filter & Cancel Telemetry Extension v1

## Goal
AI 위젯의 `생성 중단` 및 `필터 일괄 선택/해제` 행동을 서버 표준 이벤트로 수집해 UX 개선 근거를 확보한다.

## Scope
- telemetry 허용 이벤트 추가
  - `ai_widget_generation_cancel`
  - `ai_widget_filter_bulk_apply`
- payload 확장
  - `bulk_action` (`select_all` | `clear_all`)
- 메트릭 수집
  - `ai_widget_filter_bulk_action_total{action}`
  - `ai_widget_generation_cancel_scope_total{scope}`
- 검증 규칙 추가
  - `ai_widget_filter_bulk_apply` 이벤트의 `bulk_action` 유효성 검증

## Acceptance Criteria
- 신규 이벤트가 `/v1/telemetry/events`로 수집된다
- 잘못된 `bulk_action` 값은 검증 에러로 차단된다
- 단위 테스트에서 신규 카운터 증가를 검증한다
