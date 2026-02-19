# StayVista — 통합 여행 플랫폼 (OTA-style) 모노레포

## 목표
- 숙소 예약
- 티켓 예약
- 패키지 여행 예약
- 체험상품 예약(티켓과 유사)
- 챗봇 기반 여행지 추천
- 현재 위치 기반 주변 관광지 추천

## 모노레포 구조
```
.
├─ web-user/     # React (사용자)
├─ web-admin/    # React (운영/파트너/CS)
└─ services/     # Kotlin/Spring Boot (백엔드, 멀티모듈 지향)
```

## 로컬 개발 (권장)
### 1) 인프라 띄우기
```bash
./scripts/dev.sh
```

### 1-1) Local LLM(Ollama) 띄우기
```bash
./services/infra/llm/up.sh
```
- health: `GET /internal/llm/healthz`
- ready: `GET /internal/llm/readyz`

### 2) DB 마이그레이션
- Flyway 마이그레이션은 각 서비스(또는 통합 app)가 부팅 시 실행하도록 설계합니다.
- 초기 스키마는 `services/db/migrations/V1__core_tables.sql` 참고.

### 3) 백엔드 실행(현재 통합 API 앱)
```bash
./gradlew bootRun
```

### 4) 웹 실행
- web-admin: 5173
- web-user: 5180

### 5) 로컬 Seed 데이터(B-0603)
```bash
./scripts/seed_local.sh
```
- 기본값: `property` 10,000 / `room_type` 30,000 / 핫키 `inventory_night` 365일(`total=1000`)
- 환경변수로 조정:
  - `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
  - `API_BASE` (기본 `http://localhost:18765`)
  - `ADMIN_ID` (기본 `1`, reindex API의 `X-Admin-Id` 헤더)
  - `REINDEX_AFTER_SEED` (기본 `true`)
- 수동 OpenSearch 재색인:
```bash
curl -X POST -H "X-Admin-Id: 1" "http://localhost:18765/v1/admin/search/reindex?limit=10000"
```

## 구현 상태
- `src/main/kotlin/...` 아래에 v1 API(카탈로그/검색/예약/티켓/패키지/큐/지오/챗봇) 통합 앱이 구현되어 있습니다.
- `services/` 아래에는 멀티모듈 분리를 위한 스캐폴딩(`apps/*`, `libs/*`)이 준비되어 있습니다.

## 인증/결제 정책 (v1)
- 관리자 API(`/v1/admin/**`)는 `X-Admin-Id`(숫자) 헤더가 필요합니다.
- 사용자 쓰기 API(booking/ticket/package hold/confirm/cancel)는 `Authorization: Bearer <session_token>` + `Idempotency-Key`가 필요합니다.
- 결제는 `PaymentGateway` 스텁으로 처리되며, `payment_token`이 `fail`/`error`로 시작하면 `PAYMENT_AUTH_FAILED`(409)를 반환합니다.

## 핵심 원칙 (대규모 트래픽/정합성)
- **최종 정합성 보장(중복예약/과판매 방지)은 DB가 한다.**
    - 숙소(룸타입 재고형): `inventory_night(room_type_id, stay_date)`에 대해 **조건부 원자 UPDATE**
    - 티켓/체험: `ticket_inventory(event_id)`에 대해 **조건부 원자 UPDATE**
    - 공통: **Idempotency Key** + **Outbox**로 외부효과(결제/알림)를 안전하게 비동기화
- **대기열/폭주 제어**
    - 특가/핫딜/오픈런: Virtual Waiting Room(토큰/큐), rate limit, backpressure
- **Latency**
    - 검색/추천은 캐시 + 검색엔진(OpenSearch) 중심
    - 쓰기 경로는 트랜잭션을 짧게 유지하고 fan-out은 비동기(Kafka)

## 문서
- `AGENTS.md` — Codex/에이전트 작업 규칙(컨텍스트/티켓/PR 기준)
- `PLANS.md` — 단계별 로드맵
- `ARCHITECTURE.md` — 서비스/데이터/동시성/대기열/Latency 설계
- `RUNBOOK.md` — 운영 가이드(SLO/알람/장애대응)

## 티켓(backlog)
`tasks/backlog/`에 기능/인프라/UX를 세분화한 티켓이 있습니다.  
원칙: **티켓 = Codex가 한 번에 처리 가능한 thin-slice**.
