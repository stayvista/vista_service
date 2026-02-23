# B-0974 — AI Widget Slot Completion Telemetry v1

## Goal
AI 위젯의 슬롯 보완 UX가 실제로 사용되는지 측정하기 위해 슬롯 칩 클릭 이벤트를 서버 표준 이벤트로 수집한다.

## Scope
- telemetry 허용 이벤트에 `ai_widget_slot_chip_click` 추가
- 기존 `clarify_slot` 스키마 재사용
- 카운터 메트릭 수집
  - `ai_widget_slot_chip_click_total`

## Acceptance Criteria
- 슬롯 칩 클릭 이벤트가 `/v1/telemetry/events`에서 수집된다
- 유효하지 않은 슬롯 값은 기존 검증 정책에 따라 차단된다
- 단위 테스트에서 카운터 증가를 확인한다
