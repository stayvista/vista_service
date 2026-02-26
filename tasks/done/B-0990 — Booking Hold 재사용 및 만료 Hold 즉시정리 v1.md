# B-0990 — Booking Hold 재사용 및 만료 Hold 즉시정리 v1

## Goal
동일 사용자의 반복 클릭/재진입으로 동일 객실에 중복 hold가 누적되는 문제를 줄이고, 만료 hold가 즉시 정리되지 않아 발생하는 재고 드리프트를 완화한다.

## Scope
- `createHold` 경로에서 동일 사용자/동일 객실/동일 일정/동일 객실 수의 활성 hold 재사용
  - 기존 hold 만료시각 갱신
  - 최신 요청 가격 스냅샷으로 금액 갱신
- 신규 hold 시도 전에 동일 객실/일정 윈도우의 만료 hold를 on-demand 정리
- 메트릭 추가
  - `booking_hold_reused_total`
  - `booking_hold_expired_released_total`

## Acceptance Criteria
- 동일 조건으로 연속 hold 요청 시 booking row가 중복 생성되지 않는다
- 만료 hold가 남아 있어도 신규 hold 시도에서 즉시 정리 후 정상 진행된다
- 새 메트릭으로 재사용/정리 건수를 분리 집계할 수 있다
