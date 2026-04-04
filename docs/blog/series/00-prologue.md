---
title: "StayVista 기술 개발기 00: 프롤로그 - OTA에서 먼저 고정한 기술 원칙"
slug: "00-prologue"
series: "StayVista 기술 개발기"
order: 0
prev_slug: null
next_slug: "04-idempotency-key"
status: "publish-ready"
excerpt: "StayVista는 \"기능 추가 속도\"보다 먼저 \"정합성/멱등성/개발 안정성\"을 고정한 뒤 기능을 쌓은 프로젝트입니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 00: 프롤로그 - OTA에서 먼저 고정한 기술 원칙

## 한 줄 요약
StayVista는 "기능 추가 속도"보다 먼저 "정합성/멱등성/개발 안정성"을 고정한 뒤 기능을 쌓은 프로젝트입니다.

## 문제 정의
OTA(Online Travel Agency) 성격의 서비스는 기본적으로 동시성 충돌이 많습니다.

- 숙소 재고: 같은 방/같은 날짜에 동시 예약 요청이 몰립니다.
- 결제/확정: 네트워크 재시도로 같은 요청이 반복됩니다.
- 프로모션/쿠폰: 한정 수량을 여러 사용자가 동시에 점유하려 합니다.
- 검색/추천: 읽기 트래픽이 쓰기 트래픽보다 훨씬 크고, 지연에 민감합니다.

이 상황에서 앱 레벨 조건문으로만 방어하면 언젠가 과판매/중복처리/상태 꼬임이 발생합니다.

## 우리가 먼저 고정한 5가지 원칙
### 1) 정합성은 DB가 보장한다
- 핵심 불변식은 DB 조건부 UPDATE, UNIQUE 제약, 트랜잭션으로 강제합니다.
- 애플리케이션 로직은 "의사결정"을 하고, 최종 검증은 DB가 합니다.

### 2) 멱등성은 기본값이다
- 예약/결제/주문류 쓰기 API는 `Idempotency-Key`를 필수로 받습니다.
- 같은 요청 재시도는 같은 결과를 반환하고, 다른 payload면 409로 막습니다.

### 3) 쓰기 경로는 짧게, 외부효과는 Outbox로
- 도메인 write 트랜잭션 안에서 외부 시스템 호출을 하지 않습니다.
- `DB write + outbox_event write`까지만 한 트랜잭션에서 처리합니다.

### 4) 핫키 폭주는 큐와 백프레셔로 제어한다
- 단순 rate limit만으로는 오픈런/특가 트래픽을 못 버팁니다.
- queue token gate, endpoint별 rate limit, bot abuse 완화 로직을 함께 둡니다.

### 5) 관측성 없이는 회귀를 잡기 어렵습니다
- request_id/trace_id, 구조화 로그, 메트릭, 회귀 판정 기준, 체크리스트를 기능과 같이 개발합니다.
- "장애 시 무엇을 보고 어디를 만질지"가 코드와 같이 관리되어야 합니다.

## 레포에서 실제로 반영된 구조
- API/도메인 구현: `src/main/kotlin/com/devoceanblue/stayvista`
- DB 마이그레이션: `db/migration`
- 로컬 검증 자산: `services/loadtest/README.md`, `services/loadtest/k6`, `services/loadtest/grafana`, `services/loadtest/alerts`

## 연재에서 집중할 기술 축
이번 시리즈는 UI 완성도가 아니라 아래 축을 중심으로 파고듭니다.

- 동시성: Booking/Ticket/Package에서 과판매를 막는 실제 구현
- 멱등성: `idempotency_record` 기반의 중복 요청 안전 처리
- 이벤트 일관성: Outbox 릴레이와 소비자 멱등 전제
- 트래픽 제어: Queue + Rate Limit + Bot 제어
- 개발 안정성: 지연/오류 기준 기반 회귀 판정 지표와 체크리스트

## 이 시리즈를 보는 방법
- "이론"보다 "실제 코드 경로와 쿼리" 중심으로 봅니다.
- 각 편은 아래 포맷을 유지합니다.
  - 문제 상황
  - 설계 선택지 비교
  - 최종 구현
  - 테스트/관측 포인트
  - 남은 기술 부채

다음 편부터는 가장 중요한 토대인 멱등성(`Idempotency-Key`)부터 시작합니다.
