---
title: "StayVista 기술 개발기 11: [핵심] Booking CONFIRM/CANCEL - 상태 전이와 재고 복구를 안전하게 만드는 법"
slug: "11-booking-confirm-cancel"
series: "StayVista 기술 개발기"
order: 11
prev_slug: "10-booking-hold-concurrency"
next_slug: "16-db-retry-executor"
status: "publish-ready"
excerpt: "HOLD가 동시성의 입구라면, CONFIRM/CANCEL은 정합성의 출구입니다. 여기서 상태 전이가 틀리면 재고와 결제가 함께 무너집니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 11: [핵심] Booking CONFIRM/CANCEL - 상태 전이와 재고 복구를 안전하게 만드는 법

## 한 줄 요약
HOLD가 동시성의 입구라면, CONFIRM/CANCEL은 정합성의 출구입니다. 여기서 상태 전이가 틀리면 재고와 결제가 함께 무너집니다.

## CONFIRM 단계에서 실제로 어려운 점
예약 확정은 단순히 `status='CONFIRMED'` 업데이트가 아닙니다.

- 이미 만료된 HOLD를 확정하면 안 됩니다.
- 결제 승인 실패 시 재고 상태가 변하면 안 됩니다.
- 여러 날짜(booking_night)에 대한 재고 전환이 부분 성공하면 안 됩니다.

## CONFIRM 트랜잭션 핵심 흐름
`BookingService.confirmTx` 흐름은 다음과 같습니다.

1. `booking` row를 `FOR UPDATE`로 조회해 직렬화
2. 상태가 HOLD인지, 만료되지 않았는지 검증
3. 결제 승인 호출 (`PaymentGateway.authorize`)
4. `booking_night` 각 날짜에 대해 재고 전환
   - `hold -= rooms`, `sold += rooms`
   - `hold >= rooms` 조건 충족 시에만 성공
5. booking 상태를 `CONFIRMED`로 업데이트
6. `BookingConfirmed` outbox 이벤트 기록

중요한 점:
- 재고 전환은 날짜별로 조건부 UPDATE를 다시 사용합니다.
- 어느 날짜라도 실패하면 전체 트랜잭션 실패로 롤백합니다.

## 결제 경계 설계
현재 결제는 스텁 구현이며, `payment_token` prefix가 `fail|error`면 실패로 처리합니다.

이 설계의 의미:
- 결제 실패 경로를 로컬/테스트에서 항상 재현 가능
- 결제 실패가 재고/상태 전이를 오염시키지 않는지 검증 가능

실제 연동 PG 연동 시에도 이 경계는 유지되어야 합니다.

## CANCEL 단계 핵심
CANCEL은 현재 상태에 따라 서로 다른 재고 복구를 수행합니다.

- 상태가 `HOLD`면 `hold -= rooms`
- 상태가 `CONFIRMED`면 `sold -= rooms`
- 이미 `CANCELED/EXPIRED`면 상태 충돌로 거절

그리고 마지막에:
- booking 상태를 `CANCELED`로 변경
- `BookingCancelled` outbox 이벤트 기록

핵심은 "상태별 역연산"을 정확히 분리하는 것입니다.

## 만료(EXPIRE) 경로와의 관계
`expireHoldTx`는 HOLD 만료 시:
- night별 `hold` 복구
- booking 상태를 `EXPIRED`로 전환
- `BookingExpired` 이벤트를 적재

즉 CONFIRM/CANCEL/EXPIRE 세 경로는 서로 다른 상태 전이지만,
모두 같은 재고 불변식(`hold+sold<=total`) 위에서 동작해야 합니다.

## 기술적으로 중요한 방어 포인트
### 1) `FOR UPDATE`로 상태 전이 경쟁을 직렬화
동일 booking에 대한 confirm/cancel/expire 경쟁을 줄입니다.

### 2) 날짜별 조건부 UPDATE
`hold >= rooms`, `sold >= rooms` 조건을 쿼리로 강제합니다.

### 3) outbox로 후속 효과 분리
예약 상태 전이와 후속 알림/처리는 결합하지 않습니다.

### 4) 실패 지표 명시
confirm 경합 실패는 `booking_confirm_inventory_conflict_total`로 분리 관측합니다.

## 로컬 검증 지표
- `booking_confirm_requests_total`
- `booking_cancel_requests_total`
- `booking_confirm_inventory_conflict_total`
- `booking_overbooked_total{stage="confirm"}`
- `booking_expired_total`

실무에서는 confirm conflict 급증이 재고 동기화 지연, UI stale 데이터, 비정상 트래픽 중 무엇인지 함께 분석해야 합니다.

## 남은 개선 포인트
- 결제 승인과 상태 전이 사이의 타임아웃/재시도 정책 정교화
- cancel 사유 표준화(분석/정산 연동 고려)
- expired/cancel/confirm 이벤트 소비자 멱등 검증 강화

