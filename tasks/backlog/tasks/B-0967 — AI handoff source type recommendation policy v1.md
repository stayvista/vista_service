# B-0967 — AI handoff source type recommendation policy v2

## Goal
`ChatSearchHandoffAdvisor`가 추천 필터만 아니라 검색 도메인 범위(`recommended_source_types`)도 함께 산출해, UI가 의도 기반 탐색 범위를 안정적으로 선택할 수 있게 한다.

## Scope
- intent/동행/검색 hit/sourceTypes를 결합한 source type 추천 규칙 추가
- 프롬프트 기반 source type 추론 결과(`slots.sourceTypes`)를 추천 우선순위에 반영
- 명시된 source scope가 있으면 기본 intent heuristic보다 먼저 적용
- `context_used.search_handoff.recommended_source_types[]` 계약 정식화
- 추천 source type 개수 메트릭 추가
  - `chat_search_handoff_source_type_count`
- 단위 테스트 추가
  - FOOD/ATTRACTION intent에서 POI 우선
  - GENERAL fallback에서 PROPERTY 기본 포함
  - 명시 source scope(`TICKET,POI`) 우선 적용

## Acceptance Criteria
- handoff 응답에 최소 1개 이상 valid source type이 포함된다
- 추천 source type은 최대 3개, 순서가 안정적으로 재현된다
- 사용자가 “티켓/패키지/숙소”를 명시하면 추천 source type 선두에 반영된다
- 메트릭으로 추천 source type 분포를 관측할 수 있다
