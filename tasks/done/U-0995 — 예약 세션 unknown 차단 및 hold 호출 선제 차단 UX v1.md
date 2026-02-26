# U-0995 — 예약 세션 unknown 차단 및 hold 호출 선제 차단 UX v1

## Goal
예약/결제 화면에서 로컬 세션은 남아 있지만 서버 세션 검증이 `unknown`으로 떨어지는 경우에도 hold/confirm API를 호출해 `401 Unauthorized`가 노출되는 흐름을 차단한다.

## Scope
- `RequireAuth`에서 서버 세션 검증 결과가 `authenticated`가 아니면 로그인 화면으로 유도
- 숙소 상세 `지금 예약하기` 진입 전 세션 상태가 `authenticated`인지 확인하고, 그렇지 않으면 hold 호출 전 중단
- 티켓/패키지 체크아웃의 `ensureServerSession`도 동일 정책(`authenticated`만 통과)으로 통일
- 사용자 안내 문구를 "세션 확인 실패 → 재로그인" 톤으로 일관화

## Acceptance Criteria
- 세션 검증이 `unknown` 또는 `unauthorized`일 때 `/v1/bookings/holds`, `/v1/tickets/orders/holds`, `/v1/packages/*/holds` 호출이 발생하지 않는다
- 보호 라우트 진입 시 서버 세션 검증 실패 상태에서는 로그인으로 리다이렉트된다
- 예약 흐름에서 세션 드리프트가 발생해도 사용자에게 동일한 재로그인 안내가 노출된다
