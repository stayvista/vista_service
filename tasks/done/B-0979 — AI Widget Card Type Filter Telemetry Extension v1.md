# B-0979 — AI Widget Card Type Filter Telemetry Extension v1

## Goal
추천 카드 타입 필터 사용 패턴과 전환 기여도를 계량하기 위해 타입 전환 이벤트를 서버 표준 이벤트로 수집한다.

## Scope
- telemetry 허용 이벤트 추가
  - `ai_widget_card_type_filter_click`
- payload 확장
  - `target_source_type` (`ALL/PROPERTY/PACKAGE/TICKET/POI`)
  - `visible_card_count` (0~12)
- 검증 규칙
  - `ai_widget_card_type_filter_click`에서 `target_source_type`, `visible_card_count` 필수
  - 범위/허용값 벗어나면 validation error
- 메트릭 수집
  - `ai_widget_card_type_filter_target_total{target}`
  - `ai_widget_card_type_filter_scope_total{scope}`
  - `ai_widget_card_type_visible_count`

## Acceptance Criteria
- 타입 필터 클릭 이벤트가 `/v1/telemetry/events`로 수집된다
- 잘못된 target/count payload는 서버에서 차단된다
- 단위 테스트에서 신규 이벤트 카운터와 검증 실패 케이스가 확인된다
