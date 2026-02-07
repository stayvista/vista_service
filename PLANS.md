# PLANS.md — StayVista (통합 여행 플랫폼) 실서비스급 로드맵

> 목표: “아고다 스타일”의 **통합 여행 플랫폼(숙소/티켓/체험/패키지 + 추천/Geo/챗봇)** 를 **모노레포**로 구축한다.  
> 핵심: **정합성(중복/과판매 방지)**, **대기열/레이트리밋**, **낮은 Latency**, **관측/운영성**, **점진적 롤아웃(Feature Flag/Canary)**

---

## 0. 원칙 (Product/Engineering)

### 0.1 Product Principles
- “브랜드/상표/디자인”은 **참조용 UX 패턴**만 가져오고, **명칭/아이콘/문구/레이아웃 디테일은 자체 디자인**으로 구성한다.
- 사용자 흐름은 OTA 표준(검색 → 상세 → 옵션 선택 → 결제/확정 → 예약 관리)로 간다.
- 초기엔 “결제/정산”을 최소화하되(샌드박스 결제/가결제), 구조는 **실결제 확장 가능**하게 설계한다.

### 0.2 Engineering Principles
- **DB가 최종 정합성 보장**: (숙소) `room_type_inventory_day` 조건부 UPDATE / (옵션) `room_night` UNIQUE, (티켓/체험) `inventory_day` 조건부 UPDATE
- **멱등성(Idempotency)**: 예약/결제/바우처 발급 API는 idempotency-key로 중복요청 안전
- **Outbox + Kafka**: 외부효과(바우처 생성, 알림, 이메일, 검색 인덱스 업데이트 등)는 이벤트로 비동기화
- **Latency Budget**: 검색/상세는 캐시+검색엔진으로, 예약 쓰기는 “짧은 트랜잭션”으로
- **운영 가능성**: SLO/알람/런북/부하테스트/카오스테스트를 “기능”처럼 다룬다

---

## 1. 트래픽/성능 가정 (Capacity Targets)

> “수백만 사용자”는 정확한 수치가 아니라 설계 기준이다. 아래는 초기 목표치(가정)이며, Phase 5에서 재측정/조정한다.

### 1.1 Peak Profile (가정)
- DAU 100만, MAU 500만 수준까지 확장 가능하도록 설계
- Peak: 검색 5k RPS, 상세 2k RPS, 예약 생성 200 RPS, 결제/확정 100 RPS
- 핫딜/프로모션 시 예약 경합 급증(동일 상품/동일 날짜에 집중)

### 1.2 SLO (초기 목표)
- Search API: p95 < 250ms, p99 < 600ms (캐시 hit 시 p95 < 80ms)
- Detail API: p95 < 200ms, p99 < 500ms
- Booking Create(HOLD): p95 < 250ms, p99 < 700ms
- Booking Confirm: p95 < 350ms, p99 < 900ms (외부결제는 비동기/폴링)
- Availability: 99.9% (Phase 6에서 99.95%로 상향)
- Error Rate: 5xx < 0.1% (rolling 5m)

### 1.3 데이터 정합성 목표
- Overbooking: **0** (하드 제약)
- Voucher 중복발급: **0** (idempotency + outbox)
- Event 중복처리: 허용(멱등 소비로 안전)

---

## 2. 단계별 Delivery Plan (Phases)

> 각 Phase는 “Exit Criteria(출시 조건)”를 만족해야 다음으로 넘어간다.  
> 티켓 ID 규칙: Backend=B-xxxx, User Web=U-xxxx, Admin Web=A-xxxx

---

## Phase 0 — Repository / Platform Foundation (DevEx)
**목표**: “개발/테스트/배포/관측”이 되는 뼈대 확보.

### Scope
- 모노레포 규칙/코드오너/리뷰 규칙, 브랜치 전략(Trunk-based + short-lived)
- 로컬 개발 인프라(docker-compose): MySQL/Redis/Kafka/OpenSearch
- 관측(Logging/Tracing/Metrics) 기본 세팅 + 대시보드 스켈레톤
- CI: lint/test/build + 취약점/라이선스 스캔(가능한 범위)

### Exit Criteria
- `make up` 또는 `docker compose up`으로 로컬 인프라+서비스 기동
- 샘플 API 1개가 trace/metrics/log에 찍힘
- CI에서 테스트/빌드가 통과

### Tickets (예시)
- B-0001 모노레포 스캐폴딩/규칙 고정
- B-0002 로컬 인프라(MySQL/Redis/Kafka/OpenSearch)
- B-0003 Observability 골격(OpenTelemetry + metrics)
- B-0004 CI 파이프라인 + 컨벤션

---

## Phase 1 — Lodging MVP (Search → Detail → HOLD/CONFIRM)
**목표**: 숙소 “검색-상세-예약” 핵심 플로우를 실서비스급 정합성으로 구축.

### Scope
- Catalog: 숙소/룸타입/요금제/취소정책(기본)
- Search: OpenSearch 인덱스 + 필터/정렬, 캐시 전략(서치 결과/facet)
- Booking:
    - 룸타입 재고형(날짜별 inventory)
    - HOLD(만료) → CONFIRM(확정) (idempotency + outbox)
    - 동시성: 조건부 UPDATE로 과판매 방지, 데드락/락타임아웃 재시도
- User UI: “아고다 스타일” 홈 검색바/결과/상세/예약 플로우
- Admin UI: 숙소/룸타입/요금/재고 관리 + 예약 조회

### Exit Criteria (MVP Release Gate)
- 동일 룸타입/날짜에 동시 100~500 요청에서 overbooking=0
- 예약 API에 idempotency 적용 + 통합테스트
- Search/Detail p99가 목표치 근접(부하테스트 결과 포함)

### Tickets
- B-0101 Catalog: Lodging/RoomType/RatePlan CRUD
- B-0102 Search: Index + Query API + caching
- B-0103 Booking: HOLD → CONFIRM (idempotency + outbox)
- B-0104 Inventory: 조건부 UPDATE + 만료 회수 + 데드락 재시도
- U-0101 홈/검색/결과/필터
- U-0102 상세/옵션선택/예약
- U-0103 내 예약/취소(기본)
- A-0101 숙소/룸타입/요금/재고 운영
- A-0102 예약 조회/상태 변경(운영권한)

---

## Phase 2 — Ticket / Experience MVP (QR/Voucher)
**목표**: 티켓/체험 상품을 “과판매 없이” 결제/바우처까지 최소 기능으로 출시.

### Scope
- Ticket Catalog + Schedule(회차/일자) + Inventory
- Booking: 구매 → 바우처(QR) 발급(이벤트 기반) + 검표(consume)
- Admin: 상품/회차/재고/검표 운영
- User: 탐색/상세/구매/바우처 보기

### Exit Criteria
- 동일 회차 동시구매에서 oversell=0
- 바우처 중복발급=0 (idempotency/outbox)
- 검표 멱등(중복 스캔 안전)

### Tickets
- B-0201 Ticket Catalog + Inventory 모델
- B-0202 Ticket Booking + Voucher/QR 발급(outbox consumer)
- B-0203 Check-in(검표) API + 운영 로그
- U-0201 티켓/체험 리스트/상세/구매/바우처
- A-0201 티켓/체험/재고/검표 운영

---

## Phase 3 — Package Travel (Saga Orchestration)
**목표**: “숙소 + 티켓/체험” 묶음 상품을 원자적으로 예약(전체 성공/전체 실패)하도록.

### Scope
- Package product(구성/가격/옵션) 모델
- Booking Saga:
    - 구성품 HOLD를 순차/병렬로 잡고, 실패 시 보상(Release)
    - saga 상태 저장 + 재시도/타임아웃 + 관측성
- UI: 패키지 탐색/상세/구매
- Admin: 패키지 구성/판매/모니터링

### Exit Criteria
- 구성품 3개 조합에서 실패 시 보상 정상 동작(재고 누수=0)
- saga 재시도/중단 복구(runbook 포함)

### Tickets
- B-0301 Package 모델/가격 구성
- B-0302 Package booking saga(오케스트레이터) + 상태머신
- U-0301 패키지 UI
- A-0301 패키지 운영/모니터링

---

## Phase 4 — Geo Nearby + Recommendation/Chatbot
**목표**: 위치 기반 주변 POI 추천 + 챗봇 여행 추천(초기 RAG).

### Scope
- Geo:
    - POI(관광지/식당/명소) 데이터 모델 + Geohash/H3 기반 조회
    - Nearby API: 반경/카테고리/인기도, 캐시
- Chatbot:
    - “여행지 추천” + “일정 초안” (RAG: POI/상품/리뷰 요약)
    - 안전장치: 프롬프트 인젝션 방어(기본), PII 노출 방지
- UI:
    - 챗봇 패널(우측) + 추천 카드/저장
    - 내 주변 추천(지도/리스트)

### Exit Criteria
- Nearby p95 < 200ms(캐시 포함), 지도/리스트 UX 완성
- Chatbot 응답 p95 < 2.5s (스트리밍), 장애 시 graceful fallback

### Tickets
- B-0401 Geo POI + Nearby API + 캐시
- B-0402 Chatbot(RAG) + 추천/플래너
- U-0401 챗봇 UI + 주변 추천 UI

---

## Phase 5 — Scale & Reliability (실서비스급)
**목표**: 핫딜/폭주/장애/데이터 드리프트에 버티는 운영 체계 구축.

### Scope
- 대기열/토큰 게이트(Queue + ETA) + Rate limit/WAF 전략
- 부하 테스트(검색/예약/티켓) + 카오스 테스트(의존성 장애)
- 캐시 계층 고도화(결과 캐시, detail 캐시, inventory read cache)
- 데이터/인덱스 운영: reindex/alias swap, backfill, 증분 업데이트

### Exit Criteria (GA Gate)
- Peak 가정(검색 5k RPS)에서 SLO 만족(부하 테스트 리포트)
- 핫딜 시나리오에서 queue 동작 + overbooking=0
- SEV 대응 runbook + 알람 튜닝 + oncall rota(간소 버전)

### Tickets
- B-0501 대기열/토큰게이트(핫딜 보호)
- B-0502 부하/카오스 테스트 + 런북 강화
- B-0503 비용 최적화/캐시/샤딩 전략(초기)

---

## Phase 6 — Commercialization (실전 기능 확장)
**목표**: “진짜 서비스”에서 필요한 상용 기능 확장.

### Scope (우선순위 순)
- Auth/Account: 로그인/회원/세션/디바이스
- Payment: 결제(샌드박스→실PG), 환불/취소 정책 고도화
- Promotion: 쿠폰/포인트/회원등급(간단)
- Reviews/UGC: 리뷰/평점 + 신고/모더레이션
- Partner/Host: 파트너 포털(숙소/상품 공급자)

### Exit Criteria
- 결제/환불/취소가 회계적으로 추적 가능(이벤트/감사로그)
- 개인정보/보안 기본 요건 충족(암호화/권한/감사로그)

> Phase 6 티켓은 “진입 시점”에 다시 쪼갠다(요구사항 변동이 큼).

---

## 3. 크로스-커팅 트랙 (항상 병행)

### 3.1 Observability & Ops
- trace_id/request_id 표준, 구조화 로그(JSON), redaction(PII)
- 골든시그널(traffic/latency/errors/saturation) 대시보드
- 알람: SLO burn-rate + 주요 의존성(DB/Redis/Kafka/OpenSearch)

### 3.2 Data & Eventing
- outbox(쓰기 트랜잭션) → kafka(비동기) → consumer(멱등)
- 이벤트 스키마 버저닝(compatibility)
- 재처리(Replay) 전략: DLQ + backfill job

### 3.3 Performance & Concurrency
- 예약 쓰기 경로:
    - 조건부 UPDATE 중심(락 범위 최소화)
    - 짧은 트랜잭션(외부 호출 금지)
    - 재시도(backoff + jitter) 정책
- 읽기 경로:
    - 캐시 hit 최적화
    - N+1 방지, projection 쿼리, 페이징 전략

### 3.4 Security
- Secrets 관리(.env 금지, vault/ssm 가정)
- 최소권한(RBAC) + admin audit log
- 입력검증/Rate limiting/WAF
- 챗봇: 프롬프트 인젝션/데이터 유출 완화 가이드

---

## 4. Release Engineering (실서비스급 프로세스)

### 4.1 Environments
- local → dev → stage → prod
- stage는 prod 유사 설정(캐시/큐/인덱스/알람은 낮은 강도)

### 4.2 Deploy Strategy
- Blue/Green 또는 Canary(서비스 단위)
- Feature flag로 UI/API를 점진 공개
- DB migration은 “expand → migrate → contract” 원칙

### 4.3 ORR (Operational Readiness Review) 체크리스트
출시 전 최소 충족:
- SLO/대시보드/알람
- 런북(주요 장애 시나리오 3개 이상)
- 부하테스트 결과(목표 p99/오류율)
- 데이터 백업/복구 절차
- 롤백/Feature flag off 절차

---

## 5. 리스크 레지스터 (대표 장애/실패 모드)

- **Overbooking/Oversell**: 조건부 UPDATE/UNIQUE + 재시도 정책 + 대기열
- **Cache stampede**: request coalescing, TTL jitter, stale-while-revalidate
- **Hot partition**(인기 상품/날짜): queue, sharding key 설계, inventory row 분산(필요 시)
- **Event 중복/순서 문제**: 멱등 소비, outbox, consumer offset/재처리 설계
- **OpenSearch 인덱스 불일치**: alias swap, reindex, backfill job, read fallback
- **DB 장애/슬로우 쿼리**: 슬로우로그 + 인덱스/쿼리 튜닝, 커넥션 풀 보호, 서킷브레이커

---

## 6. 진행/상태 관리 규칙
- 티켓은 `tasks/backlog → doing → done`로 이동
- 각 티켓 DoD:
    - API 계약(스키마) 업데이트
    - 통합테스트(동시성 포함) 최소 1개
    - 메트릭/로그/트레이스 추가
    - RUNBOOK 링크 1개 이상

---

## 7. “다음 2주” 권장 실행 플랜 (가장 빠른 얇은 슬라이스)
1) Phase 0 완주(B-0001~0004)
2) Phase 1에서 “Search + Detail(캐시)” 먼저 오픈(B-0102 + U-0101/0102)
3) Booking HOLD만 우선 구현(B-0103/0104) + 동시성 테스트
4) Admin 최소 기능(A-0101)로 상품/재고 운영 가능하게
5) 이후 CONFIRM/취소/내예약 확장
