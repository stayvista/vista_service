# B-0968 — AI handoff clarify-required schema v1

## Goal
AI handoff가 “검색 바로 진행 가능” 상태인지, “추가 정보 보완 필요” 상태인지를 구조화된 필드로 명확히 반환한다.

## Scope
- `context_used.search_handoff` 확장
  - `clarify_required: boolean`
  - `missing_slots: string[]` (`city`, `days`, `companions`, `budget`, `preferences`)
- 누락 슬롯 감지 규칙 추가
  - 슬롯 미확정 + 필터/문장 신호 부족 조건 반영
- handoff observability 지표 추가
  - `chat_search_handoff_clarify_required_total{required}`
  - `chat_search_handoff_missing_slot_count`

## Acceptance Criteria
- 조건 정보가 부족한 요청에서는 `clarify_required=true`와 누락 슬롯 목록이 반환된다
- 조건이 충분한 요청에서는 `clarify_required=false`가 반환된다
- 누락 슬롯 수가 메트릭으로 집계된다
