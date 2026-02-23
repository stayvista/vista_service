# B-0981 — AI Widget Card Save Telemetry Extension v1

## Goal
AI 위젯의 카드 저장/해제 행동을 텔레메트리로 수집해 추천 재사용 패턴을 계량한다.

## Scope
- telemetry 허용 이벤트 추가
  - `ai_widget_card_save_click`
- payload 확장
  - `card_save_state` (`saved/unsaved`)
  - `target_source_type` (`PROPERTY/TICKET/PACKAGE/POI`)
  - `saved_card_count` (0~20)
- 검증 규칙
  - `ai_widget_card_save_click`에서 `card_save_state`, `target_source_type`, `saved_card_count` 필수
  - `target_source_type=ALL` 금지
- 메트릭 수집
  - `ai_widget_card_save_state_total{state}`
  - `ai_widget_card_save_source_type_total{source_type}`
  - `ai_widget_card_save_scope_total{scope}`
  - `ai_widget_card_save_count`

## Acceptance Criteria
- 카드 저장/해제 클릭 이벤트가 `/v1/telemetry/events`로 수집된다
- 잘못된 상태/타입/카운트 payload는 서버에서 차단된다
- 단위 테스트에서 검증/메트릭 누적 케이스가 확인된다
