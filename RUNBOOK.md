# RUNBOOK.md — 운영/장애 대응 가이드 (초안)

## 1) SLO / SLI
### 핵심 API SLO (초안)
- Search p95 < 250ms, p99 < 800ms
- Booking HOLD p95 < 300ms, p99 < 900ms
- Booking CONFIRM p95 < 400ms, p99 < 1200ms (결제/외부PG 제외)
- Error rate(5xx) < 0.5%

### 모니터링 지표
- RPS, p50/p95/p99 latency, 4xx/5xx rate
- DB: active connections, slow query, deadlock count, lock wait time
- Redis: hit rate, evictions, memory
- Kafka: consumer lag, publish errors
- OpenSearch: query latency, indexing latency, JVM heap

## 2) 공통 장애 대응
### 2.1 Latency 급증
1) Gateway에서 어떤 route가 느린지 확인 (p95/p99)
2) 의존성 확인: DB lock wait, Redis latency, OpenSearch latency, Kafka backlog
3) 즉시 조치:
    - cache TTL 상향 / hot key 보호
    - rate limit 강화
    - feature flag로 비필수 fan-out 차단
4) 원인 분석:
    - 슬로우쿼리/인덱스/풀 설정
    - N+1 / 과도한 payload
    - OpenSearch shard/segment 상태

### 2.2 예약 과판매/정합성 이슈 의심
- 우선 확인:
    - inventory_night에서 total < sold + hold 인지
    - HOLD 만료 회수 배치가 동작하는지
- 즉시 조치:
    - 해당 상품/날짜 판매 중지(플래그)
    - 사후 보상 정책(대체 제공/환불) 실행
- 재발 방지:
    - 조건부 UPDATE 영향 row 검사 누락 여부
    - 트랜잭션 경계/격리수준 검토

### 2.3 Kafka backlog(consumer lag)
- 원인: 다운스트림 장애/처리량 부족/재시도 폭주
- 조치:
    - 소비자 scale-out
    - DLQ로 우회(지속 실패 메시지)
    - outbox relay 재시도/중복 publish 방지(event_id)

## 3) 런타임 설정(초안)
- MySQL: connection pool 상한, 타임아웃, slow query log on
- Redis: maxmemory-policy 설정, eviction 알람
- JVM: heap sizing, GC logs
- Timeout:
    - gateway→service: connect/read timeout, retries with jitter

## 4) 배포/롤백
- DB 마이그레이션은 forward-only
- Feature flag로 노출을 제어
- 카나리 배포 + 자동 롤백 기준:
    - 5xx rate 급증, p99 latency 급증, saturation 증가

## 5) 카오스/부하 테스트
- 시나리오:
    - 핫딜 오픈런(동시 10만) + 예약 confirm 경합
    - OpenSearch 장애(부분) 시 graceful degradation
    - Redis 장애 시 캐시 미스 폭주 방지(thundering herd)
- 목표:
    - 정합성 유지(과판매 0)
    - SLO 달성
