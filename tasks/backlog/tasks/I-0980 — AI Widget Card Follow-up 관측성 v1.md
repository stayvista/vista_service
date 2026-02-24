# I-0980 — AI Widget Card Follow-up 관측성 v1

## Goal
추천 카드 후속질문 기능의 실효성을 관측해 탐색 생산성과 검색 전환 영향을 분석한다.

## Scope
- 이벤트: `ai_widget_card_followup_click`
- 핵심 지표
  - `ai_widget_card_followup_source_type_total{source_type}`
  - `ai_widget_card_followup_scope_total{scope}`
  - `ai_widget_card_followup_origin_total{origin}`
- 분석 관점
  - origin별(`results_card` vs `saved_card`) 사용 비율
  - source_type별 후속질문 집중도
  - 후속질문 이후 검색 전환율(연계 이벤트 기준)

## Acceptance Criteria
- 후속질문 클릭 시 지표가 누적된다
- source_type/scope/origin 태그 기반 집계가 가능하다
- 대시보드에서 저장 카드 기반 재탐색 기여를 분리해 볼 수 있다
