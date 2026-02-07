# AGENTS.md — StayVista (Roamio) Monorepo Agent Constitution

이 문서는 Codex/에이전트가 **일관된 규칙**으로 작업하도록 하는 “헌법”입니다.

## 0) 핵심 원칙
1. **정합성은 DB가 보장한다**
    - 중복예약/과판매 방지는 “애플리케이션 if문”이 아니라 **제약/락/원자 UPDATE**로 해결한다.
2. **멱등성(Idempotency)은 기본값**
    - 예약/결제/주문 API는 반드시 `Idempotency-Key`를 지원한다.
3. **쓰기 경로는 짧게, 외부효과는 Outbox**
    - 결제/알림/이메일/쿠폰발급 등은 Outbox 이벤트로 분리한다.
4. **핫키 폭주에는 대기열 + 백프레셔**
    - 오픈런/특가 대응: token gate / queue / rate limit.
5. **관측성 없으면 운영 불가**
    - request_id/trace_id, structured logging, metrics, dashboards, SLO, alert.

## 1) 레포 구조
- `web-user/` React 사용자 UI (Vite)
- `web-admin/` React 운영자 UI (Vite)
- `services/` Kotlin/Spring Boot (멀티모듈 지향)
    - `services/db/migrations/` Flyway SQL
    - `services/docker/` 로컬 인프라 docker-compose

## 2) 작업 방식 (티켓 기반)
- 모든 작업은 `tasks/backlog/*.md` 티켓을 기준으로 진행한다.
- 티켓은 **단일 PR**로 끝나야 한다.
- 티켓 산출물:
    - 코드/SQL/문서(필요 시)
    - 테스트(단위/통합)
    - 운영 포인트(메트릭/로그/알람) 최소 1개 이상 포함

## 3) 코드 규칙 (Kotlin/Spring)
- 트랜잭션 경계는 서비스 레이어에 둔다.
- JPA 사용 시:
    - `open-in-view: false`
    - N+1 방지(fetch join, batch size)
    - 엔티티는 지연로딩/불변성 고려
- 동시성:
    - 재고 차감은 **조건부 UPDATE** (영향 row=1이면 성공)
    - 필요한 경우에만 `SELECT ... FOR UPDATE`로 짧게 직렬화
- 예외:
    - DuplicateKey/OptimisticLockException/Deadlock은 **재시도 정책**을 정의한다.

## 4) API 규칙
- 모든 외부 API는 Gateway를 통해 노출한다.
- 응답에 `request_id`, `trace_id`, `server_time` 포함.
- 에러 표준:
    - `code`, `message`, `details`, `request_id`

## 5) 데이터/이벤트 규칙
- Outbox: `outbox_event` 테이블에 기록 → Kafka publish → 소비자는 idempotent 처리
- CDC/이중쓰기 금지. “DB write + outbox write”를 같은 트랜잭션에서 처리한다.

## 6) 성능/SLO 기본값(초안)
- Search API p95 < 250ms, p99 < 800ms
- Booking Confirm p95 < 400ms, p99 < 1200ms (결제 단계 제외)
- Error rate < 0.5% (5xx)

## 7) 티켓 네이밍
- Backend: `B-xxxx`
- User Web: `U-xxxx`
- Admin Web: `A-xxxx`
