# B-0984 — AI Widget Prompt Submit Method Telemetry Extension v1

## Goal
프롬프트 제출 경로(버튼/엔터/단축키/히스토리 즉시실행)를 분리 계측해 입력 UX 병목을 분석한다.

## Scope
- `ai_widget_prompt_submit` payload 확장
  - `submit_method` (`button|keyboard_enter|keyboard_shortcut|history_submit`)
- 검증 규칙
  - 허용값 외 submit_method 차단
- 메트릭 확장
  - `ai_widget_prompt_submit_method_total{method}`
  - `ai_widget_prompt_submit_scope_total{method,scope}`

## Acceptance Criteria
- 프롬프트 제출 시 submit_method가 함께 수집된다
- 잘못된 submit_method payload는 서버에서 차단된다
- 단위 테스트에서 신규 검증/메트릭 케이스가 통과한다
