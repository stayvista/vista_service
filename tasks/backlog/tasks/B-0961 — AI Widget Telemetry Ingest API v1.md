# B-0961 — AI Widget Telemetry Ingest API v4

## Goal
홈 우측 AI 컨시어지 위젯의 실제 사용 행동(오픈/요청/핸드오프)을 서버 메트릭으로 수집해 전환 퍼널 관측의 기초를 만든다.

## Scope
- `POST /v1/telemetry/events` 엔드포인트 추가
- 허용 이벤트 화이트리스트 기반 검증
- `ai_widget_*_total` 카운터 및 `ai_widget_event_total{event,source,route}` 카운터 적재
- handoff 상세 payload 수집
  - `filter_count`
  - `handoff_confidence`
  - `handoff_profile_applied`
  - `clarify_required`
  - `missing_slot_count`
  - `source_type_scope` (PROPERTY/TICKET/PACKAGE/POI 조합)
- handoff 품질 메트릭 적재
  - `ai_widget_handoff_filter_count`
  - `ai_widget_handoff_confidence`
  - `ai_widget_handoff_profile_applied_total{applied}`
  - `ai_widget_handoff_clarify_required_total{required}`
  - `ai_widget_handoff_missing_slot_count`
  - `ai_widget_handoff_scope_total{scope}`
- 위젯 이벤트 source scope 메트릭 적재
  - `ai_widget_source_scope_total{event,scope}`
- 인증 우회/레이트리밋 정책 반영
- 단위 테스트 추가

## Allowed Events
- `ai_widget_open`
- `ai_widget_prompt_submit`
- `ai_widget_followup_click`
- `ai_widget_clarify_click`
- `ai_widget_filter_apply`
- `ai_widget_search_handoff`
- `ai_widget_view_results`

## Acceptance Criteria
- 허용 이벤트는 200 응답 + 미터 증가
- 미허용 이벤트는 `VALIDATION_ERROR`로 차단
- handoff 상세 payload의 범위 검증 실패 시 `VALIDATION_ERROR`로 차단된다
- `missing_slot_count`가 허용 범위를 벗어나면 `VALIDATION_ERROR`로 차단된다
- `source_type_scope`에 허용되지 않은 타입이 포함되면 `VALIDATION_ERROR`로 차단된다
- 프론트 위젯 액션에서 비동기 전송되며 사용자 플로우를 방해하지 않음
