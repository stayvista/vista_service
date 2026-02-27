# B-0966 — AI Widget source_type_scope telemetry 표준화 v1

## Goal
AI 컨시어지 위젯 이벤트에 검색 소스 범위(source_type_scope)를 정식 필드로 수집해, “어떤 추천 도메인 범위에서 전환이 발생하는지”를 서버 관측지표로 추적한다.

## Scope
- `TelemetryEventRequest`에 `source_type_scope` 필드 추가
- scope 파싱/정규화/검증 로직 추가
  - 허용 타입: `PROPERTY`, `TICKET`, `PACKAGE`, `POI`
  - 정규화 결과: 우선순위 기반 결합 문자열(`PROPERTY+PACKAGE+POI`)
- 미터 추가
  - `ai_widget_source_scope_total{event,scope}`
  - `ai_widget_handoff_scope_total{scope}` (`ai_widget_search_handoff` 전용)
- 단위 테스트 추가
  - 정상 scope 수집 케이스
  - 비허용 scope validation 차단 케이스

## Acceptance Criteria
- 정상 scope 입력 시 200 응답 및 scope 태그 미터가 증가한다
- 비허용 타입 포함 시 `VALIDATION_ERROR`를 반환한다
- handoff 이벤트에서 scope 분포를 별도 카운터로 집계할 수 있다
