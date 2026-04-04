---
title: "StayVista 기술 개발기 88: [확장] 결제 승인 실패 계약 - Booking/Ticket CONFIRM 경계 고정하기"
slug: "88-payment-gateway-failure-contract"
series: "StayVista 기술 개발기"
order: 88
prev_slug: "87-catalog-roomtype-review-queries"
next_slug: "89-idempotency-engine-deep-dive"
status: "publish-ready"
excerpt: "결제 연동이 완성되지 않은 단계에서도 실패 계약은 먼저 고정해야 합니다. StayVista는 `PaymentGateway`를 stub으로 두되, 실패 토큰 규칙과 에러 코드/메트릭을 고정해 Booking/Ticket confirm 경계를 검증했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 88: [확장] 결제 승인 실패 계약 - Booking/Ticket CONFIRM 경계 고정하기

## 한 줄 요약
실결제 연동 이전에도 "언제 confirm을 중단할지"를 코드로 고정해야 재고 정합성 테스트가 가능합니다.

## 구현 배경
`BookingService.confirmTx`, `TicketService.confirmTx`는 재고 이동 직전에 결제 승인을 통과해야 합니다.

- 승인 성공: 재고 `hold -> sold` 이동
- 승인 실패: 상태 전이 중단, 도메인 예외 반환

결제 경계를 명확히 하기 위해 `PaymentGateway`를 독립 서비스로 분리했습니다.

## `PaymentGateway.authorize` 규칙
### 성공/실패 기준
- `paymentToken`이 `fail*` 또는 `error*`로 시작하면 실패
- 그 외는 성공

### 실패 시 동작
- `DomainException(ErrorCode.PAYMENT_AUTH_FAILED)`
- details에 `payment_method`, `reason=stub_rejection` 포함

### 메트릭
- `payment_authorize_total{result=SUCCESS|FAILED}`
- booking reference일 때 `booking_funnel_stage_total{stage=payment_authorized,...}` 추가 기록

## confirm 트랜잭션과의 결합
### Booking confirm
`paymentGateway.authorize` 성공 후에만 `inventory_night`의 `hold/sold` 이동 SQL을 수행합니다.

### Ticket confirm
승인 성공 후에만 `ticket_inventory` `hold/sold` 이동과 `VoucherIssueRequested` outbox 이벤트를 기록합니다.

즉 결제 경계가 실패하면 재고/이벤트 상태가 먼저 바뀌지 않도록 순서를 고정했습니다.

## 테스트 근거
`PaymentGatewayTest`에서 다음을 검증합니다.

- 정상 토큰 승인 성공
- `fail_*` 토큰에서 `PAYMENT_AUTH_FAILED` 발생

이 테스트로 결제 경계의 최소 실패 계약을 안정적으로 유지했습니다.

## 기술적으로 중요한 포인트
- 외부 PG 연동 전에도 실패 시나리오를 재현할 수 있어야 confirm 트랜잭션을 안전하게 검증할 수 있습니다.
- 결제 실패는 예외 문자열이 아니라 고정된 에러 코드로 표현해야 상위 도메인 처리(재시도/메시지)가 단순해집니다.
- 승인 호출 위치를 재고 이동보다 앞에 고정해야 정합성 회귀를 막을 수 있습니다.

## 남은 과제
- 실패 사유 세분화(`insufficient_funds`, `expired_card` 등)
- 멱등 승인 키(idempotency key) 전달 구조
- 결제 지연/타임아웃 시뮬레이션 테스트 추가
