---
title: "StayVista 기술 개발기 17: [핵심] Outbox Relay - DB 정합성과 이벤트 발행을 함께 지키는 방법"
slug: "17-outbox-relay"
series: "StayVista 기술 개발기"
order: 17
prev_slug: "16-db-retry-executor"
next_slug: "18-queue-backpressure"
status: "publish-ready"
excerpt: "\"도메인 데이터는 커밋됐는데 이벤트는 유실\"되거나, 반대로 \"이벤트는 나갔는데 도메인 롤백\"되는 이중쓰기 문제를 Outbox로 끊습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 17: [핵심] Outbox Relay - DB 정합성과 이벤트 발행을 함께 지키는 방법

## 한 줄 요약
"도메인 데이터는 커밋됐는데 이벤트는 유실"되거나, 반대로 "이벤트는 나갔는데 도메인 롤백"되는 이중쓰기 문제를 Outbox로 끊습니다.

## 이중쓰기 문제
예약/상품 변경 시 아래 두 작업이 동시에 필요합니다.

1. 도메인 데이터 업데이트 (MySQL)
2. 후속 처리 트리거 (Kafka 이벤트)

트랜잭션 안에서 Kafka를 직접 호출하면:
- 트랜잭션이 길어집니다.
- 외부 시스템 지연이 DB write 경로를 막습니다.
- 실패 복구가 어렵습니다.

트랜잭션 밖에서 순차 호출하면:
- DB 성공 + Kafka 실패로 이벤트 유실 가능
- Kafka 성공 + DB 롤백 불일치 가능

## Outbox 기본 설계
해결 방식은 단순합니다.

- 도메인 트랜잭션에서 도메인 변경과 `outbox_event` insert를 같이 커밋
- 별도 릴레이 잡이 `NEW` 이벤트를 읽어 Kafka로 발행
- 발행 결과를 상태(`PUBLISHED`/`FAILED`)로 반영

`outbox_event` 핵심 컬럼:

- `event_id` (중복 방지용 고유 식별자)
- `aggregate_type`, `aggregate_id`, `event_type`
- `payload_json`
- `status` (`NEW`, `PUBLISHED`, `FAILED`)

## StayVista 릴레이 구현
`OutboxRelayJob.relay()`는 5초마다 동작합니다.

1. `status='NEW'` 이벤트를 생성순으로 `LIMIT 100` 조회
2. 이벤트별 부수 처리 수행
   - Catalog 이벤트는 Search 인덱스 동기화 호출
3. Kafka 발행 (`stayvista.events`)
4. 성공 시 `PUBLISHED`, 실패 시 `FAILED`로 업데이트
5. 성공/실패 카운터 메트릭 기록

핵심 포인트:
- 도메인 write 경로에서 Kafka와 강결합하지 않습니다.
- 발행 실패가 도메인 트랜잭션 실패로 전파되지 않습니다.

## 기술적으로 중요한 설계 판단
### 1) Outbox는 "데이터", Relay는 "전달"
트랜잭션 내부에서 해야 할 일은 Outbox row를 남기는 것까지입니다.
전달은 결국 재시도 가능한 별도 관심사입니다.

### 2) 이벤트 식별자(`event_id`) 강제
at-least-once 전달 환경에서는 중복 가능성을 제거할 수 없으므로,
소비자 쪽 멱등 처리를 위한 식별자가 반드시 필요합니다.

### 3) 상태 기반 검증 가능성
`NEW/PUBLISHED/FAILED`는 단순 상태가 아니라 상태 진단 인터페이스입니다.
지연, 누적, 실패 원인을 SQL 한 번으로 볼 수 있습니다.

## 현재 구현의 현실적인 한계
현재 코드 기준으로는 `FAILED` 이벤트 자동 재시도가 없습니다.

- 장점: 실패를 즉시 명시적으로 노출
- 단점: 수동 개입 또는 추가 retry worker가 필요

또한 릴레이 쿼리는 `SKIP LOCKED`가 아닌 단순 `NEW` 조회이므로,
릴레이 인스턴스 다중화 시 중복 경쟁 제어를 추가로 고려해야 합니다.

## 로컬 검증 지표
Outbox는 아래 두 지표를 가장 먼저 봅니다.

- `outbox_published_total`
- `outbox_failed_total`

그리고 Search 동기화 실패 지표를 함께 봅니다.

- `search_index_upsert_total{result="fail"}`

`failed` 급증은 Kafka, 인덱스 동기화, payload 스키마 회귀 중 하나일 가능성이 높습니다.

## 다음 단계(고도화 방향)
- `FAILED` 재시도 정책(지수 백오프 + 상한 + DLQ 성격 테이블)
- relay 병렬화 시 `FOR UPDATE SKIP LOCKED` 도입
- 발행 재처리 도구(개발자 replay API 또는 배치)
- 이벤트 스키마 버전 필드 추가

Outbox는 복잡한 패턴처럼 보이지만, 결국 핵심은 하나입니다.
쓰기 트랜잭션은 짧게 끝내고, 외부효과는 재시도 가능한 경로로 분리하는 것.

