---
title: "StayVista 기술 개발기 94: [심화] 인증 하드닝 - PBKDF2 비밀번호 해시와 Redis 세션 수명 관리"
slug: "94-auth-password-session-hardening"
series: "StayVista 기술 개발기"
order: 94
prev_slug: "93-search-facet-taxonomy-fallback"
next_slug: "95-my-reservation-read-model-merge"
status: "publish-ready"
excerpt: "인증 신뢰성은 토큰 형식보다 저장/검증 규칙에서 결정됩니다. StayVista는 PBKDF2 비밀번호 해시 포맷과 Redis 세션 sliding expiration을 고정해 로컬 인증 경계를 단순하고 안전하게 유지했습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 94: [심화] 인증 하드닝 - PBKDF2 비밀번호 해시와 Redis 세션 수명 관리

## 한 줄 요약
인증의 핵심은 "로그인 API"가 아니라, 비밀번호 해시 정책과 세션 수명 규칙을 일관되게 지키는 것입니다.

## 비밀번호 해시 (`PasswordHasher`)
StayVista는 `PBKDF2WithHmacSHA256`을 사용합니다.

설정값:
- iterations: 기본 `180000`
- key length: 기본 `256`
- salt: 16바이트 랜덤

저장 포맷:
- `pbkdf2$<iterations>$<salt_base64url>$<hash_base64url>`

`matches`는 포맷 파싱 실패 시 false를 반환하고, 최종 비교는 `MessageDigest.isEqual`로 수행합니다.

## 회원가입/로그인 경계 (`AuthService`)
### register
- email normalize(lowercase/trim)
- hash 생성 후 `user_account` INSERT
- 중복 email은 `CONFLICT`
- 성공 시 세션 발급

### login
- 사용자 조회 후 `status=ACTIVE` 확인
- hash verify 실패 시 `UNAUTHORIZED`
- 성공 시 세션 발급

오류 메시지는 의도적으로 동일("Invalid email or password")하게 유지해 계정 유무 노출을 줄였습니다.

## Redis 세션 (`RedisSessionService`)
세션 토큰은 `svs_<random>` 형식입니다.

저장 키:
- `auth:session:{token}`

저장 필드:
- `user_id`, `email`, `name`, `issued_at`, `expires_at`

### sliding expiration
`resolvePrincipal` 성공 시 TTL을 다시 연장합니다.

즉 활동 중인 세션은 유지되고, 비활동 세션은 자연 만료됩니다.

### 만료 처리
`expires_at`이 현재보다 작으면 즉시 invalidate 후 `null` principal을 반환합니다.

## AuthGuardFilter 연계
`resolvePrincipal` 결과를 기준으로 `X-User-Id` 주입 래퍼를 생성합니다.

- 인증 필수 경로는 principal 없으면 `UNAUTHORIZED`
- 공개 경로는 principal이 있으면 부가 정보만 주입

## 테스트 근거
`PasswordHasherTest`
- seed된 demo hash와 raw password 매칭 검증

`AuthGuardFilterTest`
- bearer 누락/무효 차단
- 유효 세션으로 `/v1/me/session` 통과
- admin 헤더 검증

## 기술적으로 중요한 포인트
- 해시 포맷(알고리즘/round/salt/hash)을 명시적으로 저장해야 향후 마이그레이션이 가능합니다.
- 세션 TTL 연장 정책을 코드로 고정해야 인증 경험과 보안 규칙이 일관됩니다.
- 인증 필터와 도메인 로직을 분리하면 서비스 코드가 단순해지고 회귀 포인트가 줄어듭니다.

## 남은 과제
- 비밀번호 재해시(파라미터 상향) 전략
- 동시 세션 목록/강제 로그아웃 기능
- 로그인 실패 누적 제한
