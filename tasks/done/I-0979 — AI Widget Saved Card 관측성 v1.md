# I-0979 — AI Widget Saved Card 관측성 v1

## Goal
저장 카드 기능의 실효성을 계측해 추천 재사용률과 이탈 감소 효과를 관찰한다.

## Scope
- 이벤트: `ai_widget_card_save_click`
- 핵심 지표
  - `ai_widget_card_save_state_total{state}`
  - `ai_widget_card_save_source_type_total{source_type}`
  - `ai_widget_card_save_scope_total{scope}`
  - `ai_widget_card_save_count`
- 대시보드 관점
  - 저장 대비 해제 비율 (`saved/unsaved`)
  - source_type별 저장 집중 분포
  - 사용자별 평균 저장 카드 수(분포)

## Acceptance Criteria
- 저장/해제 동작 시 지표가 누적된다
- source_type/scope 태그 기반 집계가 가능하다
- 저장 카드 수 분포를 대시보드에서 확인할 수 있다
