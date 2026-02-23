# I-0978 — AI Widget Card List Toggle 관측성 v1

## Goal
AI 위젯 추천 카드 확장/축소 행동을 운영 지표로 가시화해 카드 탐색 UX의 실효성을 평가한다.

## Scope
- 이벤트: `ai_widget_card_list_toggle_click`
- 핵심 지표
  - `ai_widget_card_list_state_total{state}`
  - `ai_widget_card_list_scope_total{scope}`
  - `ai_widget_card_list_visible_count`
- 대시보드 관점
  - 확장 비율(`expanded/(expanded+collapsed)`)
  - scope별 확장 사용 분포
  - 확장 시 평균 노출 카드 수

## Acceptance Criteria
- 카드 더보기/접기 동작 시 지표가 누적된다
- state/scope 태그 기반 집계가 가능하다
- visible count 분포를 대시보드에서 확인할 수 있다
