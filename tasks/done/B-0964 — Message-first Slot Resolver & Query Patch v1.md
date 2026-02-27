# B-0964 — Message-first Slot Resolver & Query Patch v1

## Goal
AI 컨시어지가 현재 검색 컨텍스트에 묶이지 않고, 사용자가 대화에서 명시한 도시/일정/동행 조건을 우선 해석하도록 개선한다.

## Scope
- `ChatRoutingPolicy.extractSlots`에서 slot 우선순위를 message-first로 변경
  - `city`, `days`, `budget_krw`, `companions`
- `ChatSearchHandoffAdvisor` 응답에 검색 패치 필드 추가
  - `city`
  - `days`
  - `companions`
- 단위 테스트 추가
  - message/context 충돌 시 message 값 우선 검증
  - handoff response에 query patch 반영 검증

## Acceptance Criteria
- 사용자가 “부산 2박3일”처럼 명시하면 기존 context가 서울이어도 부산/3일로 해석된다
- search_handoff payload에 도시/일정/동행 패치 정보가 포함된다
- 기존 route 분기 및 fallback 동작 회귀가 없다
