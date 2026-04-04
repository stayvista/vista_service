---
title: "StayVista 기술 개발기 10: [핵심] Booking HOLD 동시성 제어 - 조건부 UPDATE로 과판매 0 만들기"
slug: "10-booking-hold-concurrency"
series: "StayVista 기술 개발기"
order: 10
prev_slug: "04-idempotency-key"
next_slug: "11-booking-confirm-cancel"
status: "publish-ready"
excerpt: "HOLD는 \"임시 점유\"가 아니라 동시성 전쟁의 첫 관문입니다. 여기서 실패하면 나머지 단계가 전부 무의미해집니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 10: [핵심] Booking HOLD 동시성 제어 - 조건부 UPDATE로 과판매 0 만들기

## 한 줄 요약
HOLD는 "임시 점유"가 아니라 동시성 전쟁의 첫 관문입니다. 여기서 실패하면 나머지 단계가 전부 무의미해집니다.

## 문제 상황
숙소 예약은 동일한 `room_type_id + stay_date`에 요청이 집중됩니다.

- 2개 남은 방에 10명이 동시에 결제 직전 진입
- 사용자 재시도/중복 클릭으로 HOLD 요청이 반복
- 연박 요청은 하루라도 재고가 부족하면 전체 실패해야 함

애플리케이션에서 "먼저 조회 후 if문" 방식으로 처리하면 race condition으로 과판매가 발생합니다.

## 데이터 모델 선택
StayVista는 룸 단위가 아닌 룸타입 재고형을 사용합니다.

```sql
inventory_night(
  room_type_id,
  stay_date,
  total,
  hold,
  sold
)
```

불변식:

- 모든 시점에서 `hold + sold <= total`

## 핵심 알고리즘
HOLD 생성 시 각 날짜에 대해 아래 쿼리를 실행합니다.

```sql
UPDATE inventory_night
SET hold = hold + :rooms
WHERE room_type_id = :roomTypeId
  AND stay_date = :stayDate
  AND (hold + sold + :rooms) <= total;
```

처리 규칙:
- 영향을 받은 row가 `1`이면 해당 날짜 점유 성공
- `0`이면 재고 부족으로 즉시 실패
- 연박 중 하나라도 실패하면 트랜잭션 전체 롤백

이 방식은 읽기-검증-쓰기 분리 대신, 검증과 쓰기를 한 문장으로 묶기 때문에 race에 강합니다.

## 실제 HOLD 트랜잭션 흐름
`BookingService.createHoldTx` 기준으로 보면:

1. 입력 검증
   - 최소 객실 수, check-in/check-out 유효성, 최대 숙박일 제한
2. 도메인 검증
   - 사용자 존재, 룸/숙소 ACTIVE 상태 확인
3. 사전 정리
   - 요청 구간의 만료 hold 정리
4. 재사용 탐색
   - 동일 사용자/동일 조건 hold가 있으면 재사용 및 만료 연장
5. 날짜별 조건부 UPDATE
   - 각 night 재고 점유
6. booking, booking_night 기록
   - HOLD 상태와 스냅샷 저장

## 왜 "HOLD 재사용"이 중요한가
기술적으로 두 가지 효과가 있습니다.

- 같은 조건 재진입 시 불필요한 재고 업데이트를 줄여 DB 부하 감소
- 사용자 화면에서 "방금 있던 방이 갑자기 sold-out"처럼 보이는 drift 완화

이 최적화는 UX 개선처럼 보이지만, 실제로는 write amplification을 줄이는 동시성 최적화입니다.

## 실패 시나리오와 방어
### 1) 초고경합
- 조건부 UPDATE 실패 -> `BOOKING_OVERBOOKED`로 즉시 반환
- 성공 분기만 이후 단계로 진행

### 2) 데드락/락대기
- `DbRetryExecutor`가 MySQL error code 기반 재시도
- 짧은 backoff+jitter로 재시도 폭주 완화

### 3) 중복 요청
- `IdempotencyService`로 동일 키 재실행 방지

## 로컬 검증 지표
HOLD 안정성은 아래 지표로 봐야 합니다.

- `booking_overbooked_total{stage="hold"}`
- `inventory_update_failed_total`
- `booking_hold_reused_total`
- `db_retry_total{reason=*}`

특히 `overbooked_total`과 `hold_reused_total`을 같이 보면 재고 품질 문제와 UX drift를 함께 해석할 수 있습니다.

## 테스트 관점
핵심 테스트는 "기능 정상"보다 "경합 시 무결성 유지"를 검증해야 합니다.

- 동시 요청에서 `sold + hold <= total` 불변식 유지
- 재사용 조건에서 기존 hold가 정확히 연장되는지
- 만료 hold 정리 후 재요청이 정상 동작하는지

## 이 설계의 한계와 다음 단계
- 현재는 단일 DB 기반 동시성 보장이라 shard 분산 전환 시 재설계가 필요
- 초고트래픽 이벤트에서는 queue gate와 함께 써야 효과가 완성됨
- 사용자별 soft lock 정보와 재고 표시 간의 실시간 동기화는 추가 개선 여지

다음 핵심 편에서는 이 HOLD/CONFIRM 흐름에서 외부효과를 안전하게 분리한 Outbox 릴레이를 다룹니다.

