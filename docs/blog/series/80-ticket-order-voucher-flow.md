---
title: "StayVista 기술 개발기 80: [확장] 티켓 주문/바우처 파이프라인 - HOLD, CONFIRM, 비동기 발급까지"
slug: "80-ticket-order-voucher-flow"
series: "StayVista 기술 개발기"
order: 80
prev_slug: "79-promotion-coupon-claim-concurrency"
next_slug: "81-package-saga-compensation"
status: "publish-ready"
excerpt: "티켓 도메인은 이벤트 재고와 바우처 발급이 함께 움직입니다. StayVista는 티켓 HOLD/CONFIRM과 VoucherIssueRequested 소비를 분리해 주문 정합성과 발급 재시도를 동시에 확보했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 80: [확장] 티켓 주문/바우처 파이프라인 - HOLD, CONFIRM, 비동기 발급까지

## 한 줄 요약
티켓 확정 트랜잭션에서 바우처를 즉시 발급하지 않고, outbox 이벤트로 분리하면 실패 복구가 훨씬 단순해집니다.

## 핵심 엔티티
- `ticket_event`, `ticket_inventory`
- `ticket_order`
- `voucher`

재고 불변식은 숙소와 동일하게 `hold + sold <= total`입니다.

## HOLD 흐름 (`TicketService.holdTx`)
- 이벤트 ACTIVE 상태 확인
- 조건부 UPDATE
  - `hold = hold + qty`
  - `hold + sold + qty <= total`
- `ticket_order(status='HOLD', expires_at)` 생성

HOLD는 `IdempotencyService`로 `scope=TICKET_HOLD`에서 감쌉니다.

## CONFIRM 흐름 (`TicketService.confirmTx`)
1. `ticket_order FOR UPDATE`
2. 상태(HOLD)/만료 검증
3. 결제 승인(`PaymentGateway.authorize`)
4. 재고 전환
   - `hold -= qty`, `sold += qty` (조건 `hold >= qty`)
5. 주문 상태 `CONFIRMED`
6. outbox 2종 적재
   - `TicketOrderConfirmed`
   - `VoucherIssueRequested`

즉 "주문 확정"과 "바우처 발급"을 분리했습니다.

## 바우처 비동기 발급 (`TicketVoucherIssueJob`)
스케줄러는 outbox의 `VoucherIssueRequested`를 소비해 `voucher`를 생성합니다.

- 대상 status: `PUBLISHED`, `FAILED`
- 이미 일부 발급된 경우 `existingCount` 이후 sequence만 추가
- 완료 후 outbox status를 `CONSUMED`로 전환

`FAILED`도 재시도 대상으로 포함했기 때문에, 발급 작업 자체는 반복 실행에 안전합니다.

## 바우처 검증
`validateVoucher`는 두 입력을 지원합니다.

- `voucher_id` (`vch_` prefix)
- `qr_payload`

상태별 처리:
- `REDEEMED` -> `ALREADY_USED`
- `EXPIRED` -> `EXPIRED`
- `ISSUED` -> `REDEEMED`

## 만료 정리
`expireTicketHolds()`는 `status='HOLD' AND expires_at < now` 주문을 주기적으로 회수합니다.

- 재고에서 `hold` 감소
- 주문 상태 `EXPIRED`

## 관련 테스트
- `TicketServiceTest`
  - 바우처 조회 소유권 검증
  - QR 기반 redeem 검증
- `TicketVoucherIssueJobTest`
  - outbox 소비 후 voucher 발급
  - 기존 발급분이 있을 때 중복 없는 이어쓰기 검증

## 기술적으로 중요한 포인트
### 1) 주문 확정과 발급을 동기 처리하지 않았습니다
결제 성공 후 바우처 발급 실패가 주문 롤백으로 이어지지 않도록 경계를 나눴습니다.

### 2) sequence_no로 발급 멱등성을 확보했습니다
`UNIQUE(order_id, sequence_no)`가 중복 발급을 막아줍니다.

### 3) ID 포맷을 도메인별 prefix로 통일했습니다
- 주문: `tord_`
- 바우처: `vch_`

클라이언트/서버 로그에서 식별이 쉬워집니다.

## 남은 과제
- voucher 만료 정책 및 스케줄러
- QR payload 서명/암호화 강화
- event 소비자 분리(현재 단일 job)
