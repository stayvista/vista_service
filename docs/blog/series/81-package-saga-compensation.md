---
title: "StayVista 기술 개발기 81: [확장] 패키지 오케스트레이션 - Booking/Ticket 결합과 보상 트랜잭션"
slug: "81-package-saga-compensation"
series: "StayVista 기술 개발기"
order: 81
prev_slug: "80-ticket-order-voucher-flow"
next_slug: "82-poi-nearby-geohash-rate-limit"
status: "publish-ready"
excerpt: "패키지 주문은 숙소와 티켓을 동시에 묶어야 하므로 부분 실패가 핵심 리스크입니다. StayVista는 HOLD/CONFIRM 단계마다 보상 경로를 명시해 부분 성공 상태를 최소화했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 81: [확장] 패키지 오케스트레이션 - Booking/Ticket 결합과 보상 트랜잭션

## 한 줄 요약
패키지 도메인은 단일 트랜잭션으로 끝낼 수 없는 작업이므로, 단계별 상태와 보상 경로를 코드에 명시해야 합니다.

## 모델 구조
- `package_product`
- `package_product_component`
- `package_order`

`package_product_component`는 `ACCOMMODATION`과 `TICKET`을 한 패키지에 매핑합니다.

## HOLD 오케스트레이션 (`PackageService.hold`)
1. `package_order(status='HOLDING')` 생성
2. 숙소 HOLD 호출 (`bookingService.createHold`)
3. 티켓 HOLD 호출 (`ticketService.hold`)
4. 두 HOLD의 만료시간 최소값으로 `package_order(status='HOLD')` 확정

실패 보상:
- 티켓 HOLD 실패 시, 이미 만든 숙소 HOLD를 `bookingService.cancel(PACKAGE_HOLD_FAILED)`로 보상 취소
- package_order 상태는 `FAILED`

## CONFIRM 오케스트레이션 (`PackageService.confirm`)
1. `package_order FOR UPDATE`로 잠금
2. 상태(HOLD)/만료 검증
3. 숙소 CONFIRM
4. 티켓 CONFIRM
5. 성공 시 `package_order=CONFIRMED`

실패 보상:
- 티켓 CONFIRM 실패 시 숙소 예약 취소(`PACKAGE_CONFIRM_FAILED`)
- package_order 상태를 `FAILED`로 전환

즉 성공 경로보다 실패 경로를 먼저 설계했습니다.

## 멱등성 적용
패키지 API 자체도 `IdempotencyService`로 감쌉니다.

- `scope=PACKAGE_HOLD`
- `scope=PACKAGE_CONFIRM`

그리고 내부 서브 호출에는 `pkg-hold-booking-<id>`, `pkg-confirm-ticket-<id>` 같은 고정 키를 써서 중복 실행을 제어합니다.

## 기술적으로 중요한 포인트
### 1) HOLDING 중간 상태를 별도로 둡니다
복합 오케스트레이션에서는 중간 상태가 없으면 장애 시 원인 추적이 매우 어렵습니다.

### 2) 만료시간을 최소값으로 정규화했습니다
컴포넌트별 HOLD 만료가 다르면 패키지 전체는 더 이른 시각에 맞춰야 안전합니다.

### 3) 부분 성공을 보상으로 닫습니다
티켓 실패 후 숙소를 되돌리지 않으면 패키지 정합성이 즉시 깨집니다.

## 검증 근거
- `PackageServiceTest`
  - 만료된 package hold confirm 차단
  - status 필터/limit 동작 검증
  - 컴포넌트 호출 누락 여부(mock interaction) 확인

## 남은 과제
- 다중 컴포넌트(티켓 2종 이상) 보상 순서 일반화
- package_order 상태 전이 감사 로그 테이블 추가
- 외부 결제 재시도와 패키지 보상 정책 정렬
