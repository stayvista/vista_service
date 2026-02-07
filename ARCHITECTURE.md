# ARCHITECTURE.md — StayVista (Roamio) High-level Architecture

## 1) System Context
사용자/운영자는 웹(React)로 접근하고, 모든 API는 `api-gateway`를 통해 노출됩니다.
도메인은 숙소/티켓/체험/패키지/추천/Geo/챗봇으로 구성됩니다.

## 2) 서비스 경계(초안)
- `api-gateway` (BFF 역할): auth, rate limit, request_id/trace_id, fan-out, 응답 조립
- `catalog-service`: 숙소/룸타입/요금제/정책
- `search-service`: OpenSearch 인덱싱 + 검색 API
- `booking-service`: 숙소 예약(hold/confirm/cancel)
- `ticketing-service`: 티켓/체험 상품 + 재고 + 주문
- `package-service`: 패키지 상품 + saga 오케스트레이션
- `geo-service`: 주변 POI/관광지(지오쿼리)
- `reco-service`: 추천 모델/랭킹 (초기엔 룰/휴리스틱)
- `chatbot-service`: RAG 기반 추천/플래너
- `admin-service`(optional): 운영 기능을 API로 제공

> 초기 MVP에서는 `api-gateway + catalog + booking + search`만으로 출발하고 점진 분리합니다.

## 3) 데이터 저장소
- MySQL 8: 트랜잭션/정합성(예약/재고/주문/정책/outbox/idempotency)
- Redis: 캐시, rate limit, 대기열 토큰, 세션
- Kafka: outbox publish, 비동기 워크플로(바우처 발급/알림/인덱싱)
- OpenSearch: 숙소/티켓 검색, 자동완성, 지리 검색(옵션)

## 4) 동시성/정합성 설계 (핵심)
### 4.1 룸타입 재고형(권장, OTA 기본)
재고는 `inventory_night(room_type_id, stay_date)` 한 줄로 표현합니다.

- 예약 HOLD:
    - 연박의 각 날짜에 대해 `UPDATE inventory_night SET hold = hold + :n WHERE hold + sold + :n <= total`
    - 모든 날짜가 성공해야 HOLD 성립 (하나라도 실패하면 ROLLBACK)
- CONFIRM:
    - 결제 성공 → `hold -= n`, `sold += n` (동일 트랜잭션)
- 만료:
    - `expires_at` 지난 HOLD는 배치/스케줄러로 회수(hold 감소)

### 4.2 Idempotency (중복 클릭/재시도)
- 예약/주문 API는 `Idempotency-Key` 헤더를 요구합니다.
- `idempotency_key` 테이블에 (key, request_hash, response, status)을 저장하여
    - 같은 키 재요청 시 이전 응답을 그대로 반환
    - 서로 다른 payload로 같은 키가 오면 409 반환

### 4.3 Outbox
- 예약/주문 트랜잭션에서 `outbox_event`를 함께 기록
- outbox relay가 Kafka로 publish
- 소비자는 `event_id`로 idempotent 처리

## 5) 대기열/폭주(Hot Deal)
특정 상품/날짜가 핫키가 되면:
- Gateway에서 token gate (Redis)로 1차 차단
- 큐 기반 “virtual waiting room”
- 제한된 동시 confirm만 허용(세마포어)
- 백오프/재시도, 사용자 UX(대기 화면)

## 6) Latency 전략
- 검색/추천: 캐시 + 검색엔진, p95 목표 250ms
- 쓰기(예약/주문): 트랜잭션 짧게 유지, 외부 연동은 비동기
- 이미지/정적 리소스: CDN

## 7) UI 패턴(저작권 회피)
- OTA의 일반 UX 패턴(검색바/필터/카드/예약 단계)은 “패턴”이라 참고 가능
- **로고/문구/카피/색/아이콘/레이아웃 비율/사진**은 독자 제작
- 컴포넌트 이름도 Agoda/Agoda-like 사용 금지 (예: `SearchHero`, `DealRibbon` 등 중립 명칭)

## 8) 보안/권한
- 사용자/관리자 분리
- 파트너(숙소/티켓 공급자) RBAC
- PII 암호화(필요 시 KMS), 토큰 기반 인증(OAuth2/OIDC)
