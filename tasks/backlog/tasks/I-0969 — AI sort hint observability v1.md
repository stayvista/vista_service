# I-0969 — AI sort hint observability v1

## Goal
AI 컨시어지 정렬 힌트가 실제 사용자 행동으로 이어지는지 추적할 수 있는 관측 지표를 추가한다.

## Scope
- telemetry 이벤트 `ai_widget_sort_hint_click` 허용
- `sort_value` 필드 유효성 검증
  - 허용값: `best_match`, `price_asc`, `price_desc`, `rating_desc`, `distance`
- 메트릭 추가
  - `ai_widget_sort_hint_total{sort}`
- 테스트 추가
  - 정상 클릭 수집
  - invalid sort value 거부

## Acceptance Criteria
- 정렬 칩 클릭 시 이벤트가 수집되고 `sort` 태그 메트릭이 증가한다
- 잘못된 `sort_value`는 `VALIDATION_ERROR`로 거부된다
- 기존 telemetry 이벤트 처리에 회귀가 없다
