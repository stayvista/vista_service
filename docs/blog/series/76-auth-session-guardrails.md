---
title: "StayVista 기술 개발기 76: [확장] 인증/세션 경계 - AuthGuardFilter와 Redis 세션 설계"
slug: "76-auth-session-guardrails"
series: "StayVista 기술 개발기"
order: 76
prev_slug: "75-k6-loadtest-regression-gate"
next_slug: "77-locale-fx-price-pipeline"
status: "publish-ready"
excerpt: "StayVista는 JWT 대신 Redis 세션 토큰을 선택해 로컬 개발 단계에서 인증 무효화, 만료 연장, 경로별 권한 제어를 단순하게 유지했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 76: [확장] 인증/세션 경계 - AuthGuardFilter와 Redis 세션 설계

## 한 줄 요약
인증 로직을 컨트롤러마다 흩뿌리지 않고, `AuthGuardFilter` 한 곳에서 경로별 규칙과 사용자 주입을 처리하면 도메인 코드가 크게 단순해집니다.

## 구현 배경
로컬 사이드프로젝트 단계에서는 다음 요구가 중요했습니다.

- 빠른 로그인/로그아웃
- 즉시 세션 무효화
- 공개 API와 인증 API를 명확히 분리
- 컨트롤러에서 인증 파싱 코드 제거

이 요구에 맞춰 `RedisSessionService` + `AuthGuardFilter` 조합을 선택했습니다.

## 세션 토큰 설계 (`RedisSessionService`)
세션 생성 시 토큰은 `svs_<uuid>` 형식이며, Redis `auth:session:<token>` hash에 아래 필드를 저장합니다.

- `user_id`
- `email`
- `name`
- `issued_at`
- `expires_at`

핵심 동작:
- 기본 TTL은 `stayvista.auth.session-ttl-seconds` (기본 7200초)
- 요청 성공 시 TTL을 다시 연장(sliding expiration)
- 만료/비정상 토큰은 즉시 `null` principal로 처리

## 인증 필터 경계 (`AuthGuardFilter`)
`AuthGuardFilter`는 경로를 4종류로 분리합니다.

1. bypass
- `/actuator/**`, `/internal/**`

2. public
- 검색/자동완성/가격캘린더/POI/프로모션 조회 등 읽기 엔드포인트
- 토큰이 있으면 principal을 붙여 주고, 없어도 통과

3. admin
- `/v1/admin/**`
- `X-Admin-Id` 필수 + 숫자 검증

4. user-required
- `/v1/me/**`, `/v1/tickets/orders/**`, `/v1/bookings/**`, `/v1/packages/**` 쓰기, `/v1/chat/widget/session/snapshot`
- Bearer 토큰 없거나 무효면 `UNAUTHORIZED`

## 컨트롤러 단순화 포인트
`AuthenticatedUserRequest` 래퍼가 `X-User-Id` 헤더를 주입하기 때문에, 서비스/컨트롤러는 최종적으로 `X-User-Id`만 신뢰하면 됩니다.

즉 컨트롤러에서는 아래처럼 통일됩니다.
- `@RequestHeader("X-User-Id") userId: Long`

이 방식 덕분에 인증 파싱 로직이 도메인 계층으로 새지 않습니다.

## 실제 엔드포인트 흐름
- 로그인/회원가입: `POST /v1/auth/login`, `POST /v1/auth/register`
- 로그아웃: `POST /v1/auth/logout` (Bearer 토큰 무효화)
- 세션 확인: `GET /v1/me/session`

`/v1/me/session`은 필터에서 주입한 `auth.user_email`, `auth.user_name` request attribute를 반환합니다.

## 기술적으로 중요한 포인트
### 1) 공개 API도 토큰을 선택적으로 수용했습니다
로그인 유저가 공개 API를 호출하는 경우 personalization 여지를 남기기 위함입니다.

### 2) 경로 그룹별 거부 지표를 기록합니다
`auth_guard_reject_total{reason,path_group}`로 어떤 경계에서 실패가 나는지 빠르게 확인할 수 있습니다.

### 3) 관리자 헤더 검증을 인증 필터에 고정했습니다
관리자 API가 도메인별로 서로 다른 검증을 하지 않도록 강제합니다.

## 로컬 검증 근거
- `src/test/kotlin/com/devoceanblue/stayvista/common/web/AuthGuardFilterTest.kt`
  - Bearer 누락 시 booking hold 차단
  - 유효 Bearer로 `/v1/me/session` 통과
  - `X-Admin-Id` 누락/비숫자 차단
  - 공개 엔드포인트가 인증 없이 동작하는지 검증

## 남은 과제
- refresh token 분리 전략
- 다중 디바이스 세션 조회/강제 만료 API
- 관리자 인증을 단순 헤더에서 강화된 방식으로 확장
