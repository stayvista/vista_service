---
title: "StayVista 기술 개발기 91: [심화] Traffic Guard - 비용 기반 Rate Limit과 Bot 분리"
slug: "91-traffic-guard-rate-limit-cost"
series: "StayVista 기술 개발기"
order: 91
prev_slug: "90-queue-admit-token-lua"
next_slug: "92-api-envelope-error-contract"
status: "publish-ready"
excerpt: "요청 수 제한만으로는 트래픽 품질을 지키기 어렵습니다. StayVista는 `TrafficGuardFilter`에서 엔드포인트별 정책, bot strict 한도, 요청 cost 가중치를 결합해 제한 로직을 구성했습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 91: [심화] Traffic Guard - 비용 기반 Rate Limit과 Bot 분리

## 한 줄 요약
Rate limit는 "1요청=1비용" 가정이 깨지는 순간부터 정확도가 떨어집니다. 그래서 요청 타입별 cost 가중치를 넣었습니다.

## 필터 위치와 역할
`TrafficGuardFilter`는 인증 이후, 도메인 진입 전에 실행됩니다.

역할:
- queue token 검증
- endpoint별 rate policy 적용
- bot 의심 요청 분리
- nearby 전용 제한기 적용

## 엔드포인트 정책 매핑
`ratePolicy(method, path)`에서 그룹별 분당 한도를 매핑합니다.

- `search`, `autocomplete`
- `booking_hold`, `booking_confirm`
- `package_hold`, `package_confirm`
- `chat`, `telemetry`

## 비용 가중치 (`requestCost`)
같은 그룹이라도 비용이 다르면 추가 cost를 부과합니다.

- bot 요청: `+2`
- autocomplete 1글자 질의: `+1`
- chat 대형 payload(>2048): `+1`
- 최대 cost 상한: `4`

즉 1회 요청이라도 Redis rate limiter를 여러 번 소모시켜 공격성 트래픽을 더 빨리 제한합니다.

## Bot strict 경로
`isLikelyBot(user-agent)`로 bot signature를 감지하면 principal에 `bot:` prefix를 붙입니다.

- 기본 policy limit와 `botStrictPerMinute` 중 작은 값을 사용
- 민감 경로(`chat/search/prices/autocomplete`)는 `abuse_burst` 정책을 추가 적용

## Nearby 별도 제한기
`/v1/poi/nearby`는 `NearbyTokenBucketRateLimiter`를 별도로 씁니다.

- 일반 minute bucket이 아닌 token bucket
- 거절 시 `retry_after_ms` 상세 반환

지도 드래그 패턴은 burst가 크기 때문에 별도 제한기가 더 적합했습니다.

## Redis 장애 시 동작
`RedisRateLimiter`는 예외 시 fail-open입니다.

- `rate_limit_redis_errors_total` 증가
- 요청은 허용

완전 차단보다는 서비스 연속성을 우선한 선택입니다.

## 테스트 근거
`TrafficGuardFilterTest`에서 아래를 검증합니다.

- queue token 누락/무효 차단
- booking/search/autocomplete 429 + `Retry-After`
- bot user-agent strict 제한
- public endpoint 정상 통과

## 기술적으로 중요한 포인트
- 정책 키(`policyName`)와 principal 설계가 제한 정확도를 결정합니다.
- bot 분리를 하지 않으면 정상 사용자와 악성 트래픽이 같은 버킷을 공유합니다.
- queue와 rate limit을 계층으로 분리해야 경합과 남용을 각각 제어할 수 있습니다.

## 남은 과제
- endpoint별 adaptive limit
- user-agent 외 fingerprint 신호 추가
- cost 모델 학습형 튜닝
