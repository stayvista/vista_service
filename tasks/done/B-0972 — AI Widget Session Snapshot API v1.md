# B-0972 — AI Widget Session Snapshot API v1

## Goal
프론트 로컬 세션에 의존하는 AI 위젯 상태를 서버 저장소로 승격해 멀티 디바이스/로그인 사용자 기준으로 대화 연속성을 보장한다.

## Scope
- `POST /v1/chat/widget/session/snapshot` 추가
  - 위젯 상태 스냅샷 저장(최신 n개 메시지 + handoff 상태)
  - 사용자/세션 단위 upsert
- `GET /v1/chat/widget/session/snapshot` 추가
  - 마지막 활성 스냅샷 복구
- 저장 payload 스키마/버전 관리(`schema_version`)
- TTL/보존 정책 정의(예: 7일)
- PII 최소화 정책 반영(민감 입력 마스킹/제외)
- 기본 메트릭
  - `ai_widget_snapshot_save_total{result}`
  - `ai_widget_snapshot_load_total{result}`
  - `ai_widget_snapshot_payload_bytes`

## Acceptance Criteria
- 로그인 사용자는 기기/브라우저가 달라도 최근 AI 위젯 상태를 복구할 수 있다
- 비로그인 사용자는 기존 로컬 세션 fallback이 유지된다
- 스냅샷 저장 실패가 추천/검색 플로우를 중단시키지 않는다(soft-fail)
- 스키마 변경 시 하위 호환 또는 안전한 discard가 동작한다
