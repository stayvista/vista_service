# I-0977 — AI Widget Card Type Filter 관측성 v1

## Goal
추천 카드 타입 필터가 실제 검색 handoff 및 상세 진입 전환에 기여하는지 관측한다.

## Metrics
- `ai_widget_card_type_filter_click_total`
- `ai_widget_card_type_filter_target_total{target}`
- `ai_widget_card_type_filter_scope_total{scope}`
- `ai_widget_card_type_visible_count`
- `ai_widget_view_results_total`
- `ai_widget_search_handoff_total`

## Scope
- 타입별(ALL/PROPERTY/PACKAGE/TICKET/POI) 사용 비중 대시보드 추가
- 타입 필터 클릭 이후 `view_results`/`search_handoff` 전환율 비교
- scope별(추천 범위) 타입 필터 선호도 분포 관측

## Acceptance Criteria
- 타입 필터 사용량을 타입/범위별로 조회할 수 있다
- 타입 필터 사용 후 검색 전환 성과를 비교할 수 있다
