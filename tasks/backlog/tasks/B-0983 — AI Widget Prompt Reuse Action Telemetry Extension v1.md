# B-0983 — AI Widget Prompt Reuse Action Telemetry Extension v1

## Goal
최근 요청 재사용 이벤트를 `초안 불러오기`와 `즉시 실행`으로 구분 계측해 재사용 품질을 분석한다.

## Scope
- `ai_widget_prompt_reuse_click` payload 확장
  - `reuse_action` (`draft|submit`)
- 검증 규칙
  - `ai_widget_prompt_reuse_click`에서 `reuse_action` 필수
  - 허용값 외(`draft|submit` 이외) 차단
- 메트릭 확장
  - `ai_widget_prompt_reuse_action_total{action}`

## Acceptance Criteria
- 최근 요청 재사용 클릭 시 action 값이 함께 수집된다
- 누락/잘못된 action payload는 서버에서 차단된다
- 단위 테스트에서 신규 검증/메트릭 케이스가 통과한다
