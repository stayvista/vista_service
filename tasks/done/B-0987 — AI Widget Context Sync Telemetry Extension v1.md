# B-0987 — AI Widget Context Sync Telemetry Extension v1

## Goal
검색 조건 변경 감지 배너의 실제 사용성을 계측해, 재추천/입력 동기화 UX의 효과를 측정한다.

## Scope
- 신규 이벤트: `ai_widget_context_sync_click`
- payload 확장
  - `sync_mode` (`rerun_last_prompt|context_only`)
- 검증 규칙
  - `ai_widget_context_sync_click`에서 sync_mode 필수
  - 허용값 외 차단
- 메트릭 확장
  - `ai_widget_context_sync_mode_total{mode}`
  - `ai_widget_context_sync_scope_total{mode,scope}`

## Acceptance Criteria
- 조건 동기화 액션 클릭 시 이벤트/메트릭이 수집된다
- 누락/잘못된 sync_mode payload는 서버에서 차단된다
- 단위 테스트에서 신규 검증/메트릭 케이스가 통과한다
