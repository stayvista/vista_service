---
title: "StayVista 기술 개발기 04: [핵심] Idempotency-Key를 기본값으로 만든 이유와 구현"
slug: "04-idempotency-key"
series: "StayVista 기술 개발기"
order: 4
prev_slug: "00-prologue"
next_slug: "10-booking-hold-concurrency"
status: "publish-ready"
excerpt: "중복 클릭/재시도는 \"예외 상황\"이 아니라 \"정상 상황\"입니다. 그래서 쓰기 API는 전부 멱등성으로 감쌌습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 04: [핵심] Idempotency-Key를 기본값으로 만든 이유와 구현

## 한 줄 요약
중복 클릭/재시도는 "예외 상황"이 아니라 "정상 상황"입니다. 그래서 쓰기 API는 전부 멱등성으로 감쌌습니다.

## 왜 이게 핵심인가
예약/결제 플로우에서 아래는 항상 발생합니다.

- 모바일 네트워크 불안정으로 동일 요청 재전송
- 브라우저 더블클릭
- 프론트 타임아웃 후 사용자 재요청
- 서버 처리 완료 후 응답 손실

이때 멱등성이 없으면 실제로는 한 번만 처리되어야 할 주문/예약이 여러 번 반영됩니다.

## 스키마 설계
StayVista는 공유 멱등 저장소를 `idempotency_record`로 분리했습니다.

```sql
CREATE TABLE idempotency_record (
  idem_key      VARCHAR(100) NOT NULL,
  scope         VARCHAR(50)  NOT NULL,
  request_hash  CHAR(64)     NOT NULL,
  status        VARCHAR(20)  NOT NULL, -- IN_PROGRESS, COMPLETED, FAILED
  response_json JSON NULL,
  UNIQUE KEY uk_idem (idem_key, scope)
);
```

핵심 포인트:
- `scope`를 두어 같은 키라도 도메인별 충돌을 분리
- `request_hash`로 같은 키에 다른 payload를 강제 차단
- `response_json`을 저장해 재요청에 동일 응답 반환

## 서버 처리 플로우
`IdempotencyService.execute(...)` 흐름은 다음과 같습니다.

1. payload를 canonical JSON으로 변환해 `request_hash` 생성
2. `(idem_key, scope)`로 `INSERT ... IN_PROGRESS` 시도
3. `INSERT` 성공이면 실제 action 실행
4. 성공 시 `COMPLETED + response_json` 업데이트
5. 실패 시 `FAILED` 업데이트 후 예외 전파
6. `INSERT` 충돌이면 기존 row를 조회해서 분기
   - `request_hash` 다르면 409 (`IDEMPOTENCY_REPLAY_MISMATCH`)
   - `COMPLETED`면 저장된 응답 재반환
   - 아직 진행 중이면 짧게 polling 후 여전히 미완료면 409

## 동시 요청 시나리오
같은 키 요청이 동시에 2개 들어온다고 가정하면:

- 첫 요청: `INSERT IN_PROGRESS` 성공 -> action 실행
- 두 번째 요청: `UNIQUE` 충돌 -> 기존 row 조회
  - 아직 완료 전이면 잠깐 대기
  - 완료 후에는 `response_json` 그대로 반환

결과적으로 실제 비즈니스 action은 한 번만 실행됩니다.

## 실제 적용 범위
현재 코드에서 멱등 래퍼는 아래 핵심 쓰기 경로에 적용되어 있습니다.

- Booking: `BOOKING_HOLD`, `BOOKING_CONFIRM`, `BOOKING_CANCEL`
- Ticket: `TICKET_HOLD`, `TICKET_CONFIRM`
- Package: `PACKAGE_HOLD`, `PACKAGE_CONFIRM`

즉, 예약 확정과 금전/재고에 영향을 주는 API는 모두 같은 멱등 계약을 따릅니다.

## 기술적으로 중요한 디테일
### 1) request hash canonicalization
단순 문자열 비교가 아니라 canonical JSON 기준으로 해시해야 필드 순서 차이로 오탐이 나지 않습니다.

### 2) scope 분리
전역 키 하나만 쓰면 다른 API끼리 충돌합니다. `scope`는 필수입니다.

### 3) 상태 전이 분리
`IN_PROGRESS -> COMPLETED/FAILED` 상태를 명시해야 개발자가 stuck 요청을 진단할 수 있습니다.

### 4) "진행 중" 처리 시간 제한
무한 대기 대신 짧은 polling 후 명시적 conflict를 내보내 프론트가 재시도 정책을 갖게 합니다.

## 로컬 검증 관측 포인트
멱등성은 기능이 아니라 검증 지표로 봐야 합니다.

- replay mismatch 비율
- in-progress conflict 비율
- scope별 idempotency hit 비율
- 실패 상태(`FAILED`) 누적 추이

이 지표가 올라가면 클라이언트 재시도 정책이나 네트워크 상태를 함께 봐야 합니다.

## 남은 개선 포인트
- `FAILED` 레코드에 오류 타입/코드를 저장해 사후 분석성 강화
- scope별 TTL/정리 전략 명시
- 장시간 `IN_PROGRESS` 레코드 탐지 배치

다음 편에서는 이 멱등 레이어 위에서 실제로 과판매를 막는 Booking HOLD 동시성 구현을 다룹니다.

