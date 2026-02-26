# B-0977 — AI Widget Prompt Auto-Patch Telemetry Extension v1

## Goal
프롬프트 자동 컨텍스트 보정(autopatch) 사용량과 강도를 서버 이벤트로 수집해 추천 정확도 개선 근거를 확보한다.

## Scope
- telemetry 허용 이벤트 추가
  - `ai_widget_prompt_autopatch`
- payload 확장
  - `auto_patch_count` (자동 반영 필드 수: 0~3)
- 검증 규칙
  - `ai_widget_prompt_autopatch`는 `auto_patch_count` 필수
  - `auto_patch_count` 범위 검증(0~3)
- 메트릭 수집
  - `ai_widget_prompt_autopatch_count_total{count}`
  - `ai_widget_prompt_autopatch_field_count`

## Acceptance Criteria
- autopatch 이벤트가 `/v1/telemetry/events`로 수집된다
- 누락/비정상 `auto_patch_count`는 검증 에러로 차단된다
- 단위 테스트에서 신규 카운터/요약 메트릭이 검증된다
