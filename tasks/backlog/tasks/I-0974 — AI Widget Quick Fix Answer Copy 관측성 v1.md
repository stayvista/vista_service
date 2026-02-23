# I-0974 — AI Widget Quick Fix/Answer Copy 관측성 v1

## Goal
AI 위젯에서 누락 슬롯 보완이 실제 전환으로 이어지는지, 답변 복사가 재요청/검색 전환에 어떤 영향을 주는지 관측한다.

## Metrics
- `ai_widget_quick_fix_click_total`
- `ai_widget_quick_fix_slot_total{slot}`
- `ai_widget_quick_fix_scope_total{scope}`
- `ai_widget_answer_copy_click_total`
- `ai_widget_answer_copy_scope_total{scope}`
- `ai_widget_search_handoff_total`

## Scope
- quick-fix 클릭 후 handoff 전환율/재질문율 대시보드 추가
- slot별(도시/일정/동행/예산/선호) quick-fix 활용 비중 시각화
- answer copy 이벤트와 재요청/결과보기 클릭 간 연관 지표 추가

## Acceptance Criteria
- quick-fix와 answer copy 사용량을 일별/slot별로 확인할 수 있다
- quick-fix 이후 handoff 전환 성과를 구간별로 분석할 수 있다
