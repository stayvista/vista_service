---
title: "StayVista 기술 개발기 89: [심화] Idempotency 엔진 - canonical hash와 상태 전이 설계"
slug: "89-idempotency-engine-deep-dive"
series: "StayVista 기술 개발기"
order: 89
prev_slug: "88-payment-gateway-failure-contract"
next_slug: "90-queue-admit-token-lua"
status: "publish-ready"
excerpt: "멱등성은 키 중복 차단만으로 끝나지 않습니다. StayVista는 canonical request hash, 상태 전이(`IN_PROGRESS/COMPLETED/FAILED`), 짧은 polling 재조회까지 포함해 재시도 안전성을 고정했습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 89: [심화] Idempotency 엔진 - canonical hash와 상태 전이 설계

## 한 줄 요약
멱등성의 핵심은 "중복 요청 차단"이 아니라 "같은 요청만 재사용하고, 다른 요청은 명시적으로 거절하는 계약"입니다.

## 왜 심화 설계가 필요했는가
쓰기 API에서 같은 `Idempotency-Key`가 들어와도 실제 상황은 세 가지로 나뉩니다.

- 같은 payload 재요청
- 다른 payload 재요청(클라이언트 버그/재사용 실수)
- 첫 요청이 아직 진행 중인 상황

이 세 경우를 분리하지 않으면 정합성 오류를 조기에 잡기 어렵습니다.

## 핵심 테이블
`idempotency_record`는 `(idem_key, scope)` 유니크 키를 중심으로 상태를 저장합니다.

- `request_hash` (`CHAR(64)`)
- `status` (`IN_PROGRESS`, `COMPLETED`, `FAILED`)
- `response_json`

`scope`를 분리해 `BOOKING_HOLD`와 `TICKET_CONFIRM` 같은 서로 다른 경로 충돌을 막았습니다.

## `IdempotencyService.execute` 동작
### 1) canonical hash 계산
`RequestHashUtil.sha256Canonical`이 payload를 정규화합니다.

- Map key 정렬
- nested map/list까지 재귀 정규화
- 최종 canonical JSON으로 SHA-256 계산

즉 필드 순서만 다른 동일 요청은 같은 hash가 됩니다.

### 2) 최초 생성 시도
`tryCreate`에서 `INSERT ... status='IN_PROGRESS'`를 시도합니다.

- 성공: 현재 요청이 action 실행 주체
- `DuplicateKeyException`: 이미 같은 키 요청이 존재

### 3) action 실행 후 상태 전이
- 성공: `COMPLETED + response_json` 저장
- 런타임 예외: `FAILED`로 전이 후 예외 재전파

### 4) 중복 요청 처리
`getExistingOrThrow`에서 기존 레코드를 조회해 분기합니다.

- hash 불일치: `IDEMPOTENCY_REPLAY_MISMATCH`
- `COMPLETED`: 저장된 `response_json` 역직렬화 후 반환
- 그 외: 짧은 polling 후 계속 미완료면 `CONFLICT`

polling은 `21회 * 50ms`로 약 1초 범위입니다.

## 테스트 근거
`IdempotencyServiceTest`
- 같은 key + 같은 payload: action 1회만 실행, 동일 응답 재사용
- 같은 key + 다른 payload: `IDEMPOTENCY_REPLAY_MISMATCH`

`RequestHashUtilTest`
- top-level key 순서가 달라도 같은 hash
- nested key 순서가 달라도 같은 hash
- 값이 다르면 hash가 달라짐

## 기술적으로 중요한 포인트
- key만으로는 부족하고 payload hash까지 같이 검증해야 합니다.
- `IN_PROGRESS` 상태를 명시하지 않으면 동시 요청 경계를 설명할 수 없습니다.
- 실패를 `FAILED`로 남겨야 이후 재시도/분석 기준을 만들 수 있습니다.

## 남은 과제
- `FAILED` 레코드에 원인 코드 저장
- 장시간 `IN_PROGRESS` 감지 배치
- 경로별 polling 정책 분리(예: confirm은 더 짧게)
