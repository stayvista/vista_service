# B-0973 — AI Widget Feedback Telemetry Extension v1

## Goal
AI 위젯 UX 고도화 이벤트(답변 피드백/재생성/검색 차단)를 표준 수집하여 품질 회귀를 빠르게 탐지한다.

## Scope
- `/v1/telemetry/events` 허용 이벤트 확장
  - `ai_widget_answer_feedback`
  - `ai_widget_regenerate_click`
  - `ai_widget_search_blocked`
  - `ai_widget_scope_hint_click` (프론트 송신 이벤트 정합)
- payload 확장
  - `feedback_value` (`positive|negative`)
- 검증 규칙 강화
  - `ai_widget_answer_feedback` 이벤트에서 `feedback_value` 값 검증
- 메트릭 추가
  - `ai_widget_answer_feedback_total{feedback}`

## Acceptance Criteria
- 신규 이벤트가 4xx 없이 수집된다
- 잘못된 `feedback_value`는 validation error를 반환한다
- answer feedback 이벤트 발생 시 태그 기반 카운터가 증가한다
