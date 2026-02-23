# U-0969 — AI 추천 정렬 힌트 UX v1

## Goal
AI 컨시어지가 추천한 검색 정렬(가격/거리/평점)을 위젯에서 즉시 선택·적용할 수 있도록 UX를 강화한다.

## Scope
- `search_handoff.sort_hint` 렌더링
- 정렬 힌트 칩 UI 추가
  - `price_asc`, `price_desc`, `rating_desc`, `distance`, `best_match`
- 정렬 칩 선택 시 단일 정렬만 유지되도록 상태 보정
- 정렬 칩 선택 이벤트 telemetry 연동 (`ai_widget_sort_hint_click`)

## Acceptance Criteria
- handoff payload에 sort hint가 있으면 위젯에 “추천 정렬” 섹션이 표시된다
- 사용자가 정렬 칩을 누르면 기존 sort 선택이 교체된다(중복 sort 없음)
- 검색 핸드오프 시 선택된 정렬이 URL 파라미터 `sort`로 전달된다
- 정렬 칩 클릭 이벤트가 telemetry로 수집된다
