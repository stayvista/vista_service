# B-0986 — AI Widget Context Insert Telemetry Extension v1

## Goal
원클릭 컨텍스트 삽입 기능의 실제 사용 패턴을 계측해 프롬프트 작성 UX 개선 근거를 확보한다.

## Scope
- 신규 이벤트: `ai_widget_context_insert_click`
- payload 확장
  - `context_field` (`city|dates|guests|budget|scope`)
- 검증 규칙
  - `ai_widget_context_insert_click`에서 context_field 필수
  - 허용값 외 차단
- 메트릭 확장
  - `ai_widget_context_insert_field_total{field}`
  - `ai_widget_context_insert_scope_total{field,scope}`

## Acceptance Criteria
- 컨텍스트 삽입 칩 클릭 시 이벤트/메트릭이 수집된다
- 누락/잘못된 context_field payload는 서버에서 차단된다
- 단위 테스트에서 신규 검증/메트릭 케이스가 통과한다
