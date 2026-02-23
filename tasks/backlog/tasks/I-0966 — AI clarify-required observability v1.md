# I-0966 — AI clarify-required observability v1

## Goal
AI handoff에서 “보완 필요” 상태가 과도하게 발생하는 구간을 지표와 알람으로 식별해, 추천 품질 저하를 조기 대응한다.

## Scope
- 대시보드 지표 추가
  - `chat_search_handoff_clarify_required_total{required}`
  - `chat_search_handoff_missing_slot_count`
  - `ai_widget_handoff_clarify_required_total{required}`
  - `ai_widget_handoff_missing_slot_count`
- Alert 룰 추가
  - 최근 15분 `clarify_required=true` 비율 임계치 초과
  - 평균 `missing_slot_count` 급증
- 런북에 원인 점검 절차 추가
  - 슬롯 추출 회귀
  - source scope drift
  - UI/telemetry 계약 불일치

## Acceptance Criteria
- clarify-required 비율과 누락 슬롯 분포를 대시보드에서 확인할 수 있다
- 임계치 초과 시 알람이 발생한다
- 런북으로 1차 진단/복구 경로가 제공된다
