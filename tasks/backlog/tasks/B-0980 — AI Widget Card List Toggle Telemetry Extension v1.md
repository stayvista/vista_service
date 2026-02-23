# B-0980 — AI Widget Card List Toggle Telemetry Extension v1

## Goal
추천 카드 확장/축소 사용 패턴을 계량하기 위해 카드 리스트 토글 이벤트를 표준 텔레메트리로 수집한다.

## Scope
- telemetry 허용 이벤트 추가
  - `ai_widget_card_list_toggle_click`
- payload 확장
  - `card_list_state` (`expanded/collapsed`)
  - `visible_card_count` (0~12)
- 검증 규칙
  - `ai_widget_card_list_toggle_click`에서 `card_list_state`, `visible_card_count` 필수
  - 허용값/범위 이탈 시 validation error
- 메트릭 수집
  - `ai_widget_card_list_state_total{state}`
  - `ai_widget_card_list_scope_total{scope}`
  - `ai_widget_card_list_visible_count`

## Acceptance Criteria
- 카드 더보기/접기 클릭 이벤트가 `/v1/telemetry/events`로 수집된다
- 잘못된 상태값 또는 누락 payload는 서버에서 차단된다
- 단위 테스트에서 카운터/검증 케이스가 확인된다
