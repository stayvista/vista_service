# I-0973 — AI Widget Generation Cancel/Bulk Filter 관측성 v1

## Goal
AI 위젯에서 사용자가 어디서 대화를 끊고, 어느 정도로 추천 필터를 일괄 적용하는지 계량화해 전환 개선 우선순위를 정한다.

## Metrics
- `ai_widget_generation_cancel_total`
- `ai_widget_generation_cancel_scope_total{scope}`
- `ai_widget_filter_bulk_apply_total`
- `ai_widget_filter_bulk_action_total{action}`
- `ai_widget_search_handoff_total`

## Scope
- 생성 중단율, 중단 후 재요청율, scope별 중단 분포 대시보드 추가
- 필터 일괄 선택/해제 사용 비율 및 handoff 전환율 연계 시각화
- 알람 초안
  - 생성 중단율 급증
  - 일괄 해제 비율 급증(추천 필터 품질 저하 시그널)

## Acceptance Criteria
- 생성 중단과 필터 일괄 액션의 일별 추이를 확인할 수 있다
- 검색 handoff 전환과 연계해 품질 저하 지점을 식별할 수 있다
