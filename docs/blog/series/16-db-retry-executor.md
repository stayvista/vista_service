---
title: "StayVista 기술 개발기 16: [핵심] DB 재시도 전략 - deadlock/lock wait를 실패가 아니라 제어 가능한 상태로"
slug: "16-db-retry-executor"
series: "StayVista 기술 개발기"
order: 16
prev_slug: "11-booking-confirm-cancel"
next_slug: "17-outbox-relay"
status: "publish-ready"
excerpt: "고경합 시스템에서는 deadlock이 \"버그\"가 아니라 \"상수\"입니다. 핵심은 무조건 재시도가 아니라, 재시도 가능한 실패만 짧게 다시 시도하는 것입니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 16: [핵심] DB 재시도 전략 - deadlock/lock wait를 실패가 아니라 제어 가능한 상태로

## 한 줄 요약
고경합 시스템에서는 deadlock이 "버그"가 아니라 "상수"입니다. 핵심은 무조건 재시도가 아니라, 재시도 가능한 실패만 짧게 다시 시도하는 것입니다.

## 왜 재시도가 필요한가
Booking/Ticket 같은 경합 경로에서는 아래 예외가 자연스럽게 발생합니다.

- MySQL deadlock (`errorCode=1213`, `sqlState=40001`)
- lock wait timeout (`errorCode=1205`)

이때 즉시 실패만 하면 정상 사용자도 대량 실패를 경험합니다.

## 현재 구현
`DbRetryExecutor.execute`는 RuntimeException 내부에서 SQL 예외를 추출해 분기합니다.

- 재시도 대상:
  - `1213` (deadlock)
  - `1205` (lock wait timeout)
  - `40001` (serialization/deadlock 계열)
- 백오프:
  - `50ms`, `150ms`, `350ms` + `0~40ms jitter`
- 최대 시도:
  - 총 3회

재시도 시 `db_retry_total{reason=deadlock|lock_wait}` 메트릭을 증가시킵니다.

## 기술적으로 중요한 설계 포인트
### 1) "모든 예외 재시도" 금지
비즈니스 검증 실패(예: 재고 부족, 상태 충돌)는 재시도해도 성공하지 않습니다.
재시도 대상을 SQL 경합 계열로 제한해야 합니다.

### 2) 짧은 백오프 + jitter
동시 실패가 몰린 시점에 모두 즉시 재시도하면 2차 폭주가 납니다.
지터는 herd effect를 줄이는 데 필수입니다.

### 3) 재시도는 트랜잭션 바깥에서 감싼다
현재 구조는 `retryExecutor.execute { transactionTemplate.execute { ... } }` 형태입니다.
각 재시도마다 트랜잭션을 새로 시작하므로 안전합니다.

### 4) 상한이 있는 재시도
무한 재시도는 지연과 장애 전파를 키웁니다.
3회 제한은 사용자 지연과 성공 가능성 사이의 절충점입니다.

## 적용 위치
재시도 래퍼는 고경합 쓰기 경로에서 주로 사용됩니다.

- Booking: hold/confirm/cancel
- Ticket: hold/confirm
- Package: 구성품 hold/confirm 체인

## 로컬 검증 해석 방법
`db_retry_total`는 높다고 무조건 문제는 아닙니다.

- 정상: 트래픽 증가 시 deadlock retry가 일정 수준 동반
- 위험: retry가 급증하면서 성공률이 동반 하락

함께 봐야 할 지표:
- `booking_overbooked_total`
- `booking_confirm_inventory_conflict_total`
- API p95/p99 지연

## 개선 아이디어
- reason별 백오프 튜닝(예: lock wait는 더 긴 backoff)
- attempt 횟수/소요시간 히스토그램 추가
- 특정 SQL fingerprint에서 retry 과다 시 재현 테스트 기준 추가

재시도는 "예외를 숨기는 장치"가 아니라, 경합이 많은 시스템에서 실패를 제어 가능한 형태로 바꾸는 안정화 장치입니다.
