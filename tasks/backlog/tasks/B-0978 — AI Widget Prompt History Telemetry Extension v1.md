# B-0978 — AI Widget Prompt History Telemetry Extension v1

## Goal
최근 요청 재사용 기능 사용량과 효과를 계량하기 위해 prompt history 클릭 이벤트를 서버 표준 이벤트로 수집한다.

## Scope
- telemetry 허용 이벤트 추가
  - `ai_widget_prompt_reuse_click`
- payload 확장
  - `reuse_rank` (1~5)
- 검증 규칙
  - `ai_widget_prompt_reuse_click`에서 `reuse_rank` 필수 및 범위 검증
- source allowlist 확장
  - `prompt_history`
- 메트릭 수집
  - `ai_widget_prompt_reuse_rank_total{rank}`
  - `ai_widget_prompt_reuse_scope_total{scope}`

## Acceptance Criteria
- prompt history 클릭 이벤트가 `/v1/telemetry/events`로 수집된다
- 범위를 벗어난 `reuse_rank`는 검증 에러로 차단된다
- 단위 테스트에서 신규 카운터와 검증 로직이 확인된다
