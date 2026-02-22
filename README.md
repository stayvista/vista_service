# StayVista — 통합 여행 플랫폼 (OTA-style) 모노레포

## 목표
- 숙소 예약
- 티켓/체험 예약
- 패키지 여행 예약
- AI 기반 여행 추천
- 지도 기반 주변 추천

## 최근 반영 사항
- 홈 메인에 카드 섹션 확장:
  - `지금 가장 빠르게 예약되는 호텔`
  - `대한민국 내 인기 여행지`
  - `숙소 세일`
  - `즐길 거리 프로모션`
  - `추천 숙소`
  - `대한민국 외 인기 여행지`
- 도시 카드 클릭 시 검색 화면(`/search`)으로 이동하며 도시/날짜/투숙인원 파라미터를 함께 전달합니다.
- 기간/수량 제한 쿠폰 발급 기능 추가:
  - 캠페인 조회: `GET /v1/promotions/campaigns`
  - 쿠폰 발급: `POST /v1/promotions/campaigns/{campaignId}/claim`
  - DB: `promotion_campaign`, `promotion_coupon_claim`

## 모노레포 구조
```text
.
├─ web-user/     # React (사용자)
├─ web-admin/    # React (운영/파트너/CS)
├─ src/          # Kotlin/Spring Boot 통합 API 앱
├─ db/migration/ # Flyway SQL
└─ services/     # 멀티모듈 분리용 스캐폴딩 + 부가 컴포넌트
```

## 로컬 개발
### 1) 인프라 기동
```bash
./scripts/dev.sh
```
- mysql: `127.0.0.1:23306`
- redis: `127.0.0.1:26379`
- kafka: `127.0.0.1:39092,39093,39094`
- opensearch: `127.0.0.1:39200`

### 1-1) Local LLM(Ollama) 기동 (선택)
```bash
./services/infra/llm/up.sh
```
- Ollama endpoint: `http://127.0.0.1:23434`
- health: `GET /internal/llm/healthz`
- ready: `GET /internal/llm/readyz`

### 2) 백엔드 실행
```bash
./gradlew bootRun
```
- API 기본 포트: `18765`

### 3) 웹 실행
```bash
npm --prefix web-user install
npm --prefix web-user run dev
```
- web-user: `5180`

```bash
npm --prefix web-admin install
npm --prefix web-admin run dev
```
- web-admin: `5173`

## DB 마이그레이션/시드
### Flyway
- 마이그레이션 위치: `db/migration`
- 앱 부팅 시 Flyway 자동 실행

### Seed 데이터
```bash
./scripts/seed_local.sh
```
- 기본값:
  - `property` 20,000
  - `room_type` 60,000
  - `poi` 12,000
  - `inventory_night` 365일(`total=1000`)
- 프로모션/쿠폰 캠페인 시드 포함(`V12__home_promotions_coupon.sql`)
- 로컬 mysql client 인증 플러그인 이슈 발생 시 docker mysql client로 자동 재시도
- 환경변수:
  - `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
  - `API_BASE` (기본 `http://localhost:18765`)
  - `ADMIN_ID` (기본 `1`)
  - `REINDEX_AFTER_SEED` (기본 `true`)

수동 OpenSearch 재색인:
```bash
curl -X POST -H "X-Admin-Id: 1" "http://localhost:18765/v1/admin/search/reindex?limit=20000"
```

## 인증/결제 정책 (v1)
- 관리자 API(`/v1/admin/**`): `X-Admin-Id`(숫자) 헤더 필요
- 사용자 쓰기 API:
  - `Authorization: Bearer <session_token>`
  - `Idempotency-Key` (예약/결제 계열)
- 프로모션 쿠폰 발급(`POST /v1/promotions/campaigns/{campaignId}/claim`)은 로그인 사용자 필요
- 결제는 `PaymentGateway` 스텁 기반이며 `payment_token`이 `fail`/`error`로 시작하면 `PAYMENT_AUTH_FAILED`(409)

## 핵심 원칙
- 정합성은 DB 제약/원자 UPDATE/락으로 보장
- 멱등성(Idempotency) 기본 적용
- 외부효과는 Outbox 이벤트로 분리
- 핫키 폭주는 대기열/백프레셔로 제어
- 운영은 메트릭/로그/알람 기반

## 문서
- `AGENTS.md` — 에이전트 작업 규칙
- `PLANS.md` — 단계별 로드맵
- `ARCHITECTURE.md` — 서비스/데이터/동시성 설계
- `RUNBOOK.md` — 운영 가이드(SLO/알람/장애 대응)

## 티켓(backlog)
- `tasks/backlog/`에 기능/인프라/UX 티켓이 정리되어 있습니다.
- 원칙: **티켓 = 한 번에 처리 가능한 thin-slice**.
