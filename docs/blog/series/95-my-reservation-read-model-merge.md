---
title: "StayVista 기술 개발기 95: [심화] My 예약 통합 Read Model - 도메인 3종 집계와 정렬 전략"
slug: "95-my-reservation-read-model-merge"
series: "StayVista 기술 개발기"
order: 95
prev_slug: "94-auth-password-session-hardening"
next_slug: "96-customer-inquiry-state-validation"
status: "publish-ready"
excerpt: "마이페이지 예약 목록은 booking/ticket/package 쓰기 모델을 그대로 노출하면 사용성이 떨어집니다. StayVista는 도메인별 조회를 분리한 뒤 통합 정렬해 read model로 제공했습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 95: [심화] My 예약 통합 Read Model - 도메인 3종 집계와 정렬 전략

## 한 줄 요약
예약 통합 조회는 테이블 통합이 아니라 조회 모델 통합 문제였습니다. 쓰기 모델을 유지하면서 읽기 시점에만 합치는 방식으로 복잡도를 낮췄습니다.

## 문제 정의
사용자 관점의 "내 예약"은 실제로 서로 다른 테이블에서 나옵니다.

- 숙소: `booking`
- 티켓: `ticket_order`
- 패키지: `package_order`

이 데이터를 DB 단일 UNION으로 강제하면 스키마 변화 때마다 영향 범위가 커집니다.

## 구현 구조 (`MyReservationService`)
### 0) API 입력 경계 (`MeController`)
`GET /v1/me/reservations`는 컨트롤러 기본값을 `limit=50`으로 받고, 서비스에서 다시 `1..200`으로 보정합니다.

즉 잘못된 큰 값이 들어와도 조회 범위가 통제되며, 너무 작은 값(0 이하)도 자동 보정됩니다.

### 1) 사용자 검증
`DomainSupportService.getActiveUser(userId)`로 활성 사용자만 허용합니다.

### 2) 요약 카운트
`countByTable`을 도메인별로 호출해 상단 카운트를 분리 계산합니다.

- `booking`
- `ticket_order`
- `package_order`

### 3) 도메인별 상세 조회
각 로더가 도메인 특화 SQL을 사용합니다.

- `loadBookingItems`
- `loadTicketItems`
- `loadPackageItems`

### 4) 통합 정렬
각 로더 결과를 `ReservationWithSortAt(createdAt)`로 받고,
`createdAt DESC` 정렬 후 최종 `limit`만 적용합니다.

## 쿼리 구성과 비용
요청 1회는 최소 다음 순서로 실행됩니다.

- 활성 사용자 조회 1회
- 도메인별 카운트 3회 (`booking`, `ticket_order`, `package_order`)
- 도메인별 목록 조회 3회

즉 기본적으로 7개의 쿼리를 사용합니다. 구현은 단순하지만 트래픽이 커지면 카운트 3개를 캐시하거나 비동기화하는 최적화가 필요합니다.

## 도메인별 subtitle/식별자 정책
통합 리스트에서도 상세 맥락이 보이도록 subtitle을 도메인별로 다르게 구성합니다.

- booking: `check_in~check_out`, 객실 수, room name
- ticket: event start time, 수량
- package: 연결 booking/ticket ID

조회 SQL에서 `COALESCE`를 사용해 누락 데이터도 안전하게 표시합니다.

- `숙소 정보 없음`
- `객실 정보 없음`
- `티켓 상품 정보 없음`
- `패키지 상품 정보 없음`

식별자 prefix도 일관되게 유지합니다.

- `bkg_`, `tord_`, `pkg_`

## limit 처리 전략
입력 `limit`는 `1..200`으로 보정합니다.

중요한 점은 각 도메인 로더에 동일 limit를 적용한 뒤, 마지막 병합 단계에서 다시 상위 limit를 적용한다는 점입니다. 이 방식은 구현이 단순하지만, 특정 도메인 편중 데이터에서는 과조회가 발생할 수 있습니다.

## 계측
- `my_reservations_requests_total`

현재는 요청량 중심 계측만 있으므로, 후속으로 도메인별 item count 분포를 추가하면 성능 튜닝에 도움이 됩니다.

## 기술적으로 중요한 포인트
- 쓰기 모델을 억지로 합치지 않아도 읽기 모델은 충분히 통합할 수 있습니다.
- 통합 모델에서는 공통 필드와 도메인 확장 필드를 명확히 분리해야 합니다.
- ID prefix 규칙을 고정해두면 프론트 분기 로직이 단순해집니다.
- 조회 모델에서 fallback 문구를 고정해두면 데이터 공백 시에도 UI 동작이 예측 가능합니다.

## 남은 과제
- cursor 기반 페이지네이션
- 도메인별 lazy fetch(현재는 eager merge)
- 상태 라벨 표준화 함수 분리
