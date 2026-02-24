# B-0982 — AI Widget Card Follow-up Telemetry Extension v1

## Goal
추천 카드 후속질문 액션의 사용량과 전환 기여도를 계측할 수 있도록 텔레메트리를 확장한다.

## Scope
- telemetry 허용 이벤트 추가
  - `ai_widget_card_followup_click`
- payload 검증 규칙
  - `target_source_type` 필수 (`PROPERTY/TICKET/PACKAGE/POI`)
  - `target_source_type=ALL` 금지
- 허용 source 확장
  - `results_card`, `saved_card`
- 메트릭 수집
  - `ai_widget_card_followup_source_type_total{source_type}`
  - `ai_widget_card_followup_scope_total{scope}`
  - `ai_widget_card_followup_origin_total{origin}`

## Acceptance Criteria
- 저장 카드/실시간 카드 후속질문 클릭 이벤트가 `/v1/telemetry/events`로 정상 수집된다
- 필수 필드 누락/잘못된 타입 payload는 서버에서 차단된다
- 단위 테스트에서 신규 검증 및 메트릭 누적이 확인된다
