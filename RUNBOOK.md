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

### 2.4 인증/권한 4xx 급증
1) `/v1/admin/**` 호출이 `403/400`이면 `X-Admin-Id` 헤더 누락/비숫자 여부 확인
2) booking/ticket/package 쓰기 호출이 `401/400`이면 `X-User-Id` 헤더 누락/비숫자 여부 확인
3) 클라이언트에서 `Idempotency-Key`가 요청마다 안정적으로 전달되는지 확인

### 2.5 결제 승인 실패(409 PAYMENT_AUTH_FAILED)
1) 결제 토큰 형식 확인 (`fail`/`error` prefix는 테스트 실패 토큰으로 취급)
2) `payment_authorize_total{result=FAILED}` 메트릭 급증 여부 확인
3) 주문/예약 상태가 `HOLD`에 남아있는지 확인하고 만료 배치 동작 확인

### 2.6 Local LLM 지연/대기열 급증
1) `/internal/llm/healthz`, `/internal/llm/readyz` 상태 확인
2) 애플리케이션 지표 확인
   - `llm_ms`
   - `llm_timeout_count`
   - `llm_error_count`
   - `llm_used_rate`
   - `route_clarify_rate`, `route_template_rate`, `route_llm_rate`
   - `llm_inflight`
   - `llm_queue_depth`
   - `llm_queue_wait_ms`
   - `llm_reject_rate`
   - `chat_rag_ms`
   - `chat_rag_index_ms`
   - `citation_verifier_block_total`
   - `chat_memory_total`
   - `chat_pref_profile_total`
   - `chat_reranker_proxy_score_before`
   - `chat_reranker_proxy_score_after`
3) 즉시 조치
   - `CHAT_LLM_ENABLED=false`로 LLM 경로를 즉시 차단하고 TEMPLATE로 degrade
   - `stayvista.chat.llm.max-concurrency` 하향/상향 조정
   - `stayvista.chat.llm.max-queue-wait-ms` 단축하여 빠른 degrade
   - `stayvista.chat.llm.active-model`을 더 작은 모델로 전환
4) 재발 방지
   - prompt/retrieval cache hit ratio 개선
   - 룰/템플릿 라우팅 비율 상향(LLM 사용률 절감)
   - memory summary 길이/PII 마스킹 점검 (`CHAT_MEMORY_TTL_SECONDS`, `CHAT_PREFERENCE_TTL_SECONDS`)

## 3) Local LLM 운영 절차 (Ollama)

### 3.1 서비스 기동
```bash
./services/infra/llm/up.sh
```
또는
```bash
docker compose -f services/docker/docker-compose.yml --profile llm up -d
```

### 3.2 health / ready
```bash
./services/infra/llm/healthz.sh
./services/infra/llm/readyz.sh
```

또는 API:
- `GET /internal/llm/healthz`
- `GET /internal/llm/readyz`

### 3.3 워밍업 (cold vs warm)
```bash
./services/infra/llm/warmup.sh
```
- 첫 요청(cold) 대비 두 번째 요청(warm) 지연이 감소하는지 확인한다.
- 운영 반영 전 최소 1회 워밍업 실행을 권장한다.

### 3.4 모델 교체 / 롤백
```bash
# 교체
./services/infra/llm/swap-model.sh llama3.1:70b-instruct bge-m3

# 롤백
./services/infra/llm/swap-model.sh llama3.1:8b-instruct bge-m3
```
- 앱 설정 반영:
  - `LLM_MODEL_CHAT` (또는 `CHAT_LLM_ACTIVE_MODEL`)
  - `CHAT_EMBED_ACTIVE_MODEL`
- 순서: pull/warmup -> 일부 트래픽 확인 -> 전체 전환

### 3.5 RAG 인덱스 재빌드
```bash
curl -sS -X POST \"http://localhost:18765/v1/admin/chat/rag/reindex?mode=full\"
curl -sS -X POST \"http://localhost:18765/v1/admin/chat/rag/reindex?mode=incremental&limit=1000\"
```
- 런타임 검색은 `travel_doc*` 인덱스를 사용하므로, 카탈로그 변경 후 incremental 재빌드를 권장한다.

### 3.6 선호 피드백 반영
```bash
curl -sS -X POST "http://localhost:18765/v1/chat/preferences/feedback" \
  -H "Content-Type: application/json" \
  -d '{"user_id":"1001","like_tags":["culture"],"like_categories":["POI"]}'
```

## 4) 런타임 설정(초안)
- MySQL: connection pool 상한, 타임아웃, slow query log on
- Redis: maxmemory-policy 설정, eviction 알람
- JVM: heap sizing, GC logs
- Timeout:
    - gateway→service: connect/read timeout, retries with jitter

## 5) 배포/롤백
- DB 마이그레이션은 forward-only
- Feature flag로 노출을 제어
- 카나리 배포 + 자동 롤백 기준:
    - 5xx rate 급증, p99 latency 급증, saturation 증가

## 6) 카오스/부하 테스트
- 시나리오:
    - 핫딜 오픈런(동시 10만) + 예약 confirm 경합
    - OpenSearch 장애(부분) 시 graceful degradation
    - Redis 장애 시 캐시 미스 폭주 방지(thundering herd)
- 목표:
    - 정합성 유지(과판매 0)
    - SLO 달성
