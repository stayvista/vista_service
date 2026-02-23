# B-0960 — Chat Context Enrichment v2 (search context aware)

## Goal
홈/검색 UI 상태(도시/날짜/인원)를 Chat 슬롯 추론에 반영해 동일 질문에서 추천 품질의 변동을 줄인다.

## Scope
- `ChatRoutingPolicy.extractSlots`에서 context 기반 보강
  - `check_in/check_out` -> `days` 추론
  - `guests` -> `companions` 추론
- 기존 message 파서와 context 파서를 병행하여 degrade 안전성 유지
- 단위 테스트 추가

## Rules
- context 값이 비정상이면 기존 message 기반 추론으로 fallback
- 날짜/게스트는 recommendation route 판단에만 사용하고, 가격/재고 확정값으로 사용하지 않음

## Acceptance Criteria
- 날짜 context만 있는 요청에서도 `days`가 안정적으로 채워진다
- guests context만 있는 요청에서도 동행 타입이 안정적으로 채워진다
- 기존 routing 테스트 회귀 없음
