# I-0986 — AI Widget Search Blocked 관측성 v1

## Goal
AI 위젯에서 검색 전 차단 원인과 추천 범위(scope)를 함께 관측해, 재추천 유도 UX의 실효성과 병목 구간을 파악한다.

## Scope
- 이벤트: `ai_widget_search_blocked`
- 핵심 지표
  - `ai_widget_search_block_reason_total{reason}`
  - `ai_widget_search_block_scope_total{reason,scope}`
- 분석 관점
  - reason별 발생 비율(`missing_slots` vs `context_drift`)
  - scope 결합 시 차단 편중 구간
  - 차단 이후 재추천/검색 재진입 전환율

## Acceptance Criteria
- 차단 원인을 reason 단위로 분리 집계할 수 있다
- scope 결합 지표로 추천 범위별 차단 패턴 분석이 가능하다
- 대시보드에서 차단→재추천→검색 진입 퍼널 구성이 가능하다
