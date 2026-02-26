# B-0989 — Me Session Probe API & Auth Reject Metric v1

## Goal
프론트엔드가 예약 요청 전 서버 세션 유효성을 빠르게 확인할 수 있도록 경량 세션 프로브 API를 제공하고, 인증 거절 상황을 메트릭으로 집계한다.

## Scope
- `GET /v1/me/session` API 추가
  - 인증 통과 시 사용자 기본 식별 정보 반환
- `AuthGuardFilter` 인증 거절 시 메트릭 추가
  - `auth_guard_reject_total{reason,path_group}`

## Acceptance Criteria
- 유효 세션 토큰으로 `/v1/me/session` 호출 시 200 응답을 반환한다
- 토큰 미존재/무효 상태에서 보호 엔드포인트 접근 시 `auth_guard_reject_total`이 증가한다
- 인증 실패 응답 포맷(`UNAUTHORIZED`, `request_id`)은 기존 표준을 유지한다
