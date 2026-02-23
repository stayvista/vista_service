# I-0972 — AI Widget Slot Completion 관측성 v1

## Goal
슬롯 체크리스트 UX 도입 이후 어떤 슬롯에서 사용자가 반복적으로 막히는지 계량화해 개선 우선순위를 정한다.

## Metrics
- `ai_widget_slot_chip_click_total`
- `ai_widget_clarify_action_slot_total{slot}`
- `ai_widget_handoff_missing_slot_count`
- `ai_widget_search_blocked_total`

## Scope
- 슬롯별 클릭/보완 패널 추가
- 검색 차단 비율과 누락 슬롯 분포를 함께 시각화
- 알람 초안
  - 특정 슬롯 누락 비율 급증
  - 검색 차단율 임계 초과

## Acceptance Criteria
- 슬롯별 병목(도시/일정/동행/예산/선호)을 일 단위로 확인 가능하다
- 검색 차단과 슬롯 누락이 연결된 원인을 대시보드에서 파악할 수 있다
