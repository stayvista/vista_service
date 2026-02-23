# I-0967 — AI clarify action observability v1

## Goal
누락 슬롯 보완 UX의 실제 사용률과 효과를 측정해, AI 컨시어지의 질의 완결률을 개선한다.

## Scope
- 이벤트 수집 확장
  - `ai_widget_clarify_action_click`
  - `clarify_slot`
- 지표 추가
  - `ai_widget_clarify_action_slot_total{slot}`
  - `chat_search_handoff_clarify_action_count`
  - `chat_search_handoff_clarify_action_total{slot}`
- 알람/런북 점검 항목
  - 슬롯별 액션 클릭 편중
  - clarify-required 대비 액션 클릭 저조 구간

## Acceptance Criteria
- 슬롯별 clarify action 클릭량을 대시보드에서 확인할 수 있다
- invalid slot telemetry는 validation error로 차단된다
- 클릭량 저하 또는 슬롯 편중 탐지 기준이 운영 문서에 반영된다
