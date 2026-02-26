# B-0985 — AI Widget Error Recovery Telemetry Extension v1

## Goal
오류 복구 액션 사용 패턴을 계측해 실패 후 이탈을 줄일 개선 근거를 확보한다.

## Scope
- 신규 이벤트: `ai_widget_error_recovery_click`
- payload 확장
  - `recovery_action` (`retry|restore_draft|reset_scope|dismiss`)
- 검증 규칙
  - `ai_widget_error_recovery_click`에서 recovery_action 필수
  - 허용값 외 차단
- 메트릭 확장
  - `ai_widget_error_recovery_action_total{action}`
  - `ai_widget_error_recovery_scope_total{action,scope}`

## Acceptance Criteria
- 오류 복구 액션 클릭 시 이벤트/메트릭이 수집된다
- 누락/잘못된 recovery_action payload는 서버에서 차단된다
- 단위 테스트에서 신규 검증/메트릭 케이스가 통과한다
