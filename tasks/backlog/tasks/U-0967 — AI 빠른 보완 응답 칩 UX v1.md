# U-0967 — AI 빠른 보완 응답 칩 UX v1

## Goal
AI handoff가 누락 슬롯을 감지한 경우, 사용자가 텍스트를 다시 입력하지 않아도 한 번의 클릭으로 보완 응답을 전송할 수 있게 한다.

## Scope
- handoff payload의 `clarify_actions[]` 파싱 및 위젯 상태 저장
- 누락 슬롯별 “빠른 응답 칩” UI 노출
- 액션 칩 클릭 시
  - prompt 자동 전송
  - search_patch 선반영
  - source_type_scope 연속성 유지
- 텔레메트리 이벤트 추가
  - `ai_widget_clarify_action_click`
  - `clarify_slot`

## Acceptance Criteria
- 누락 슬롯이 있으면 빠른 응답 칩이 노출된다
- 칩 클릭 시 사용자 입력 없이 다음 추천이 생성된다
- 칩이 포함한 search_patch가 다음 검색 handoff에 반영된다
- `ai_widget_clarify_action_click` 이벤트가 슬롯 정보와 함께 수집된다
