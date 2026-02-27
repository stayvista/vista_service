# B-0971 — AI handoff sort recommendation policy v1

## Goal
AI 컨시어지 handoff가 필터뿐 아니라 정렬 전략까지 제안해 검색 전환 품질을 높인다.

## Scope
- `ChatSearchHandoffAdvisor`에 정렬 힌트 규칙 추가
  - 메시지 키워드/의도 기반 `sort` 추천
  - 예: 가성비→`price_asc`, 이동 동선→`distance`, 품질/후기→`rating_desc`
- `recommended_filters`에 `sort` 필터 포함
- `context_used.search_handoff.sort_hint` 스키마 추가
  - `value`, `label`, `reason`
- preferences 누락 시 정렬 성향 보완 액션 칩 생성 규칙 추가
- 메트릭 추가
  - `chat_search_handoff_sort_hint_total{sort}`

## Acceptance Criteria
- 예산/가성비 요청에서 `sort=price_asc` 추천이 생성된다
- 출장/동선 요청에서 `sort=distance` 추천이 생성된다
- handoff payload에 `sort_hint`가 구조화되어 포함된다
- 정렬 힌트 메트릭이 sort 태그 기준으로 집계된다
