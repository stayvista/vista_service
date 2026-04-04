---
title: "StayVista 기술 개발기 90: [심화] Queue Admit Token - Redis ZSET + Lua + HMAC"
slug: "90-queue-admit-token-lua"
series: "StayVista 기술 개발기"
order: 90
prev_slug: "89-idempotency-engine-deep-dive"
next_slug: "91-traffic-guard-rate-limit-cost"
status: "publish-ready"
excerpt: "대기열은 단순 순번이 아니라 입장 권한 계약입니다. StayVista는 Redis ZSET과 Lua 원자 연산으로 admit를 수행하고, HMAC 서명 토큰으로 보호 엔드포인트 접근을 검증합니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 90: [심화] Queue Admit Token - Redis ZSET + Lua + HMAC

## 한 줄 요약
Queue는 "기다리기" 기능이 아니라 "허용 가능한 쓰기 트래픽만 통과시키는 권한 시스템"입니다.

## QueueService 데이터 구조
Queue key별로 Redis 키를 분리합니다.

- 대기열: `queue:zset:{queueKey}`
- 입장 집합: `queue:admitted:{queueKey}`
- subject dedupe: `queue:joined:{queueKey}:{subject}`
- 티켓 메타: `queue:ticket:{ticketId}`

`join`은 subject 기반 dedupe를 먼저 확인해 동일 사용자의 우선순위 교란을 막습니다.

## admit 원자 연산 (`popAndAdmitScript`)
핵심은 Lua 스크립트 한 번에 아래를 처리하는 것입니다.

1. admitted 만료 엔트리 정리 (`ZREMRANGEBYSCORE`)
2. admitted 수량 확인 (`ZCARD`)
3. 여유가 있으면 대기열에서 가장 오래된 티켓 pop (`ZPOPMIN`)
4. admitted 집합에 만료 시각으로 추가 (`ZADD`)

이 과정을 원자적으로 실행해 race condition을 줄였습니다.

## 상태 조회와 토큰 발급
`status(ticket)` 호출 시 내부에서 `admit(queueKey)`를 먼저 수행합니다.

- admitted에 있으면 `ADMITTED` + `admit_token` 반환
- 아니면 `WAITING` + `position/estimated_wait_seconds` 반환
- 티켓 만료면 `EXPIRED`

예상 대기시간은 근사치로 계산합니다.

- `position / maxAdmittedPerKey * admitTokenTtlSeconds`

## Admit token 포맷
토큰은 `payload.signature` 형식입니다.

- payload 원문: `queueKey|ticketId|expiresAt|nonce`
- payload Base64 URL 인코딩
- signature: `HmacSHA256(secret)`

`validateAdmitToken`은 다음을 모두 확인합니다.

- 서명 일치
- payload 파싱 가능
- 토큰 자체 만료 여부
- admitted ZSET에 아직 살아있는 티켓인지

## TrafficGuardFilter 연동
`stayvista.queue.enabled=true`이면 보호 경로에서 `Queue-Token`이 필수입니다.

보호 경로:
- booking hold/confirm
- ticket hold/confirm
- package hold/confirm

토큰이 없거나 무효면 즉시 `QUEUE_REQUIRED`, `QUEUE_TOKEN_INVALID`를 반환합니다.

## 계측
- `queue_join_total`
- `queue_admitted_total`

대기열 진입과 실제 admit를 분리 계측해 큐 병목을 볼 수 있게 했습니다.

## 기술적으로 중요한 포인트
- Queue admit는 원자 연산(Lua)으로 처리해야 경합에서 순번 정합성이 유지됩니다.
- 토큰 유효성은 서명만 보지 않고, admitted 집합 상태까지 확인해야 합니다.
- subject dedupe가 없으면 한 사용자가 다수 티켓으로 큐를 오염시킬 수 있습니다.

## 남은 과제
- QueueService 단위 테스트 보강
- queue 길이/평균대기시간 지표 추가
- key 간 공정성 제어(글로벌 fairness) 정책
