# B-0969 — AI handoff clarify actions schema v1

## Goal
clarify-required 상태에서 프론트가 즉시 실행 가능한 구조화 액션을 받을 수 있도록 handoff 스키마를 확장한다.

## Scope
- `context_used.search_handoff`에 `clarify_actions[]` 필드 추가
  - `slot`: `city|days|companions|budget|preferences`
  - `label`: UI 칩 라벨
  - `prompt`: 자동 전송할 보완 메시지
  - `search_patch`: city/days/companions 선반영 값
  - `recommended_source_types`: 액션 전용 추천 소스 범위
- 누락 슬롯별 액션 자동 생성 규칙 추가
- handoff 지표 확장
  - `chat_search_handoff_clarify_action_count`
  - `chat_search_handoff_clarify_action_total{slot}`

## Acceptance Criteria
- clarify-required 응답에서 `clarify_actions[]`가 반환된다
- 액션은 누락 슬롯과 일치하는 슬롯 값으로 생성된다
- 액션 개수/슬롯 분포가 메트릭으로 수집된다
