---
title: "StayVista 기술 개발기 18: [핵심] Queue Backpressure - 오픈런에서 쓰기 API를 보호하는 구조"
slug: "18-queue-backpressure"
series: "StayVista 기술 개발기"
order: 18
prev_slug: "17-outbox-relay"
next_slug: "19-rate-limit-abuse"
status: "publish-ready"
excerpt: "폭주 상황에서 API를 \"모두 받는 것\"은 친절이 아니라 장애의 시작입니다. Queue는 허용량만 통과시키고 나머지를 질서 있게 대기시킵니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 18: [핵심] Queue Backpressure - 오픈런에서 쓰기 API를 보호하는 구조

## 한 줄 요약
폭주 상황에서 API를 "모두 받는 것"은 친절이 아니라 장애의 시작입니다. Queue는 허용량만 통과시키고 나머지를 질서 있게 대기시킵니다.

## 문제 정의
핫딜/오픈런에서는 특정 상품/날짜로 트래픽이 집중됩니다.

- `POST /holds`, `POST /confirm` 요청이 순간적으로 폭증
- DB 락 경합 증가, 실패율 상승, tail latency 악화
- 정상 사용자도 함께 실패

단순 rate limit만으로는 "누가 먼저 들어왔는지"를 보장하기 어렵습니다.

## 현재 Queue 구조
`QueueService`는 Redis 기반 가상 대기실을 제공합니다.

- join:
  - 대기열(`ZSET`)에 ticket 추가
  - 사용자 중복 join 방지(dedupe key)
- status:
  - 현재 순번/예상 대기시간 반환
  - admission 가능 시 짧은 수명의 admit token 발급
- token:
  - HMAC 서명 payload
  - 만료 시간 + admitted 상태를 함께 검증

핵심 Redis 스크립트:
- `ZREMRANGEBYSCORE`로 만료 admission 정리
- 수용 여유가 있으면 `ZPOPMIN`으로 가장 오래 기다린 ticket admit

## API 보호 연동
`TrafficGuardFilter`에서 queue 보호 엔드포인트를 정의합니다.

- `/v1/bookings/holds`
- `/v1/bookings/{id}/confirm`
- `/v1/tickets/orders/holds`
- `/v1/tickets/orders/{id}/confirm`
- `/v1/packages/{id}/holds`
- `/v1/packages/{id}/confirm`

`stayvista.queue.enabled=true`일 때:
- `Queue-Token` 헤더 필수
- 토큰 누락/무효면 즉시 에러 반환

## 기술적으로 중요한 포인트
### 1) dedupe join
같은 사용자가 새 ticket를 무한 발급받아 우선순위를 교란하지 못하게 막습니다.

### 2) admission TTL
admit token은 짧게(기본 30초) 유지하여 유휴 점유를 줄입니다.

### 3) queue와 app 인증 분리
queue token은 "순번 통과 권한"이고, 사용자 인증 토큰은 별도입니다.
역할을 섞지 않아야 보안/성능 해석이 명확합니다.

### 4) 예상 대기시간은 근사치
`position`, `maxAdmittedPerKey`, `admitTokenTtl` 기반 추정값이므로 UX 가이던스 용도로 써야 합니다.

## 로컬 검증 지표
- `queue_join_total`
- `queue_admitted_total`
- queue 보호 경로의 4xx/latency
- queue ON/OFF 비교 시 funnel 성공률 변화

부하 테스트에서는 queue OFF/ON을 비교해 다음을 봅니다.
- 429/5xx 비율
- p95/p99 지연
- confirm 성공률

## 현재 구현의 한계
- queue fairness는 key 단위이며, 전역 공정성은 별도 정책이 필요
- queue 길이/대기시간 메트릭이 더 있으면 검증 가시성이 좋아짐
- 멀티 리전/멀티 큐 분산은 후속 과제

Queue는 트래픽을 막기 위한 기능이 아니라, 시스템이 감당 가능한 처리량 안에서 성공률을 보존하기 위한 안전장치입니다.

