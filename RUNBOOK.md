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
   - `chat_semantic_cache_total`
   - `chat_llm_budget_mode`
   - `chat_llm_budget_p99_ms`
   - `chat_experiment_assignment_total`
   - `chat_shadow_total`
   - `chat_prompt_registry_total`
   - `abuse_block_total`
3) 즉시 조치
   - `CHAT_LLM_ENABLED=false`로 LLM 경로를 즉시 차단하고 TEMPLATE로 degrade
   - `stayvista.chat.llm.max-concurrency` 하향/상향 조정
   - `stayvista.chat.llm.max-queue-wait-ms` 단축하여 빠른 degrade
   - `stayvista.chat.llm.active-model`을 더 작은 모델로 전환
   - `stayvista.chat.llm.budget.*` 임계값을 조정해 자동 degrade 강도를 변경
   - `stayvista.chat.semantic-cache.similarity-threshold`를 0.88~0.95 범위에서 튜닝
4) 재발 방지
   - prompt/retrieval cache hit ratio 개선
   - 룰/템플릿 라우팅 비율 상향(LLM 사용률 절감)
   - memory summary 길이/PII 마스킹 점검 (`CHAT_MEMORY_TTL_SECONDS`, `CHAT_PREFERENCE_TTL_SECONDS`)

### 2.7 지도 타일/스타일 장애
1) 증상 확인
   - 프론트에서 타일 404/429/5xx 급증, 스타일 JSON fetch 실패, 빈 타일 발생 여부 확인
2) 즉시 조치
   - 지도 스타일 URL을 백업 소스로 전환
   - Nearby 페이지 `auto-search` 비활성화(버튼 재검색 모드 유지)로 요청량 억제
   - 클러스터 반경/줌 정책을 보수적으로 조정해 렌더링 부하 축소
3) 모니터링
   - `http_server_requests_seconds_*{uri=\"/v1/poi/nearby\"}`
   - `cache_hit_rate_nearby_total`
   - `rate_limited_count_total{endpoint_group=\"nearby\"}`
4) 복구 체크
   - 지도 초기 렌더링 시간 정상화
   - Nearby API p95/p99 정상화
   - 429/5xx 비율 정상화

### 2.8 프로모션 쿠폰 발급 장애
1) 주요 지표 확인
   - `promotion_campaign_list_total`
   - `promotion_claim_total{result=*}` (`success`, `already_claimed`, `sold_out`, `out_of_window`, `inactive`)
2) 증상별 즉시 조치
   - `sold_out` 급증: `promotion_campaign.issue_limit`, `issued_count`를 확인하고 필요 시 운영자 정책으로 증량/종료
   - `out_of_window` 급증: 캠페인 `starts_at`, `ends_at` 타임존/서버시간(NTP) 확인
   - `inactive` 급증: 캠페인 상태(`status`) 및 배포 데이터 점검
3) 정합성 점검 쿼리
   - `SELECT code, issue_limit, issued_count FROM promotion_campaign WHERE id = ?;`
   - `SELECT COUNT(*) FROM promotion_coupon_claim WHERE campaign_id = ?;`
   - 두 값이 불일치하면 트랜잭션 실패/수동 조작 이력을 확인한다.

### 2.9 AI handoff detail/clarify 드리프트
1) 주요 지표 확인 (Grafana: `StayVista AI Copilot SLO`)
   - `ai_widget_handoff_filter_count`
   - `ai_widget_handoff_confidence`
   - `ai_widget_handoff_filter_count_by_scope{scope=*}`
   - `ai_widget_handoff_confidence_by_scope{scope=*}`
   - `ai_widget_handoff_profile_applied_total{applied=*}`
   - `ai_widget_handoff_scope_total{scope=*}`
   - `ai_widget_source_scope_total{event=ai_widget_search_handoff,scope=*}`
   - `chat_search_handoff_clarify_suggested_total`
   - `chat_search_handoff_clarify_required_total{required=*}`
   - `chat_search_handoff_missing_slot_count`
   - `chat_search_handoff_clarify_action_count`
   - `chat_search_handoff_clarify_action_total{slot=*}`
   - `ai_widget_clarify_click_total`
   - `ai_widget_clarify_action_slot_total{slot=*}`
   - `chat_search_handoff_clarify_question_count`
   - `ai_widget_handoff_confidence_by_clarify{state=*}`
   - `ai_widget_handoff_filter_count_by_clarify{state=*}`
2) 알람 기준
   - `ChatSearchHandoffProfileAppliedRatioLow`
   - `ChatSearchHandoffClarifySuggestedRatioHigh`
   - `ChatSearchHandoffClarifyCtrLow`
   - `ChatSearchHandoffScopeDriftHigh`
   - `ChatSearchHandoffClarifyRequiredRatioHigh`
   - `ChatSearchHandoffMissingSlotCountHigh`
   - `ChatSearchHandoffClarifyActionClickLow`
   - `ChatSearchHandoffClarifyActionSlotSkewHigh`
3) 1차 대응 순서
   - `clarify_suggested_ratio` 상승 + `clarify_ctr` 하락 동시 발생 시:
     - 슬롯 추출 규칙(`city/days/companions/budget/preferences`) 민감도 완화
     - 기본 추천 전송 임계치 하향으로 clarify 의존도 감소
   - `clarify_required_ratio`와 `missing_slot_count` 동시 상승 시:
     - handoff advisor 슬롯 추출 회귀 여부 확인 (`ChatRoutingPolicy.extractSlots`)
     - 프론트 컨텍스트 전달값(city/dates/guests) 누락 여부 확인
     - 최근 배포에서 prompt template 변경점 우선 롤백 검토
   - `profile_applied_ratio` 하락 시:
     - 프로필 키 누락/만료 확인 (`chat_pref_profile_total{status=miss}`)
     - 위젯 handoff payload의 `handoff_profile_applied` 필드 누락 여부 확인
   - `scope_drift_ratio` 상승 시:
     - `ai_widget_source_scope_total{event=ai_widget_search_handoff}`와 `ai_widget_handoff_scope_total`를 scope별 비교
     - 프론트 payload `source_type_scope` 직렬화 형식(`PROPERTY+POI`) 불일치 여부 확인
     - route별 fallback 비율(`ai_widget_event_total{event=ai_widget_orchestrator_fallback}`) 급증 여부 확인
   - `clarify clicked` 대비 `not_clicked` 품질 역전 시:
      - `ai_widget_handoff_confidence_by_clarify`와 `ai_widget_handoff_filter_count_by_clarify` 비교
      - clarify 문구를 액션형(버튼/칩)으로 단순화하고 질문 수 상한(권장 2개) 적용
   - `clarify_action_click` 저조 또는 slot 편중 시:
     - `ai_widget_clarify_action_slot_total`와 `chat_search_handoff_clarify_action_total` 비교해 프론트 누락 여부 확인
     - 특정 slot 편중(`>75%`)이면 해당 slot 추출 키워드 가중치 하향 및 다른 slot 힌트 보강
4) 재발 방지
   - 주간 리뷰에서 scope별(`PROPERTY/TICKET/PACKAGE/POI`) clarify 제안율/CTR 비교
   - `ai_widget_search_handoff` 샘플 이벤트 50건을 추출해 누락 슬롯/과다질문 케이스 점검

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

# 카나리 단독 실행
./services/infra/llm/canary.sh llama3.1:70b-instruct
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

### 3.7 Prompt 롤백 / A-B / Shadow 운영
```bash
# prompt 등록/활성화
curl -sS -X POST "http://localhost:18765/v1/admin/chat/prompts" \
  -H "Content-Type: application/json" \
  -d '{"prompt_key":"chat-core","version":"v3","system_prompt":"...","activate":true}'

# prompt 즉시 롤백
curl -sS -X POST "http://localhost:18765/v1/admin/chat/prompts/rollback" \
  -H "Content-Type: application/json" \
  -d '{"prompt_key":"chat-core","version":"v2"}'

# A/B rollout 조정 (0/5/50/100)
curl -sS -X POST "http://localhost:18765/v1/admin/chat/experiments/chat-core" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true,"rollout_percent":50,"treatment_model":"llama3.1:70b-instruct","prompt_version":"v3"}'
```

### 3.8 Chat SLO Burn-rate Alert 점검(staging)
```bash
./services/loadtest/alerts/staging_alert_smoke.sh
```
- alert rules 파일: `services/loadtest/alerts/chat_slo_burn_rate_rules.yml`
- staging에서 k6 스파이크 후 expression/alert 상태를 재확인한다.

### 3.9 추천 큐레이션 운영(Admin)
```bash
# TOP_PICK 등록
curl -sS -X POST "http://localhost:18765/v1/admin/chat/curation/rules" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Id: 9001" \
  -d '{"doc_id":"property:1001","rule_type":"TOP_PICK","weight":180,"enabled":true}'

# BLACKLIST 등록
curl -sS -X POST "http://localhost:18765/v1/admin/chat/curation/rules" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Id: 9001" \
  -d '{"doc_id":"poi:900","rule_type":"BLACKLIST","enabled":true}'
```
- 큐레이션은 chat retrieval 시 즉시 반영된다(재기동/재색인 불필요).

### 3.10 AI Copilot 오케스트레이터 점검
1) 엔드포인트 정상성
```bash
curl -sS -X POST "http://localhost:18765/v1/chat/copilot/orchestrate" \
  -H "Content-Type: application/json" \
  -d '{"message":"서울 가족여행 숙소 추천","session_state":{"destination":"Seoul","date_range":{"check_in":"2026-03-02","check_out":"2026-03-04"},"guests":{"rooms":1,"adults":2,"children":1,"children_ages":[7]}}}'
```
- 응답 필수 필드: `answer`, `actions[]`, `evidence[]`, `confidence`, `tool_trace[]`, `request_id`, `trace_id`

2) 메트릭 확인
- `chat_copilot_orchestrator_latency_ms`
- `chat_copilot_orchestrator_requests_total{result=*}`
- `chat_copilot_orchestrator_tool_total{tool=*,status=*}`
- `chat_copilot_orchestrator_no_result_total`
- `ai_copilot_funnel_step_total{step=*}`
- `ai_copilot_action_apply_total{result=*}`
- `ai_copilot_quality_event_total{metric=*}`
- `chat_copilot_guardrail_violation_total{reason=*}`

3) 알람 발생 시 1차 대응 순서
- `CopilotOrchestratorLatencyP99High`:
  - 실패 툴 상위 확인: `chat_copilot_orchestrator_tool_total{status="failed"}`
  - `get_price_calendar` 또는 `check_availability` 실패 급증 시 해당 툴 fallback 경로 활성화
- `CopilotWidgetErrorRateHigh`:
  - `ai_widget_orchestrator_fallback`/`ai_widget_search_blocked` 이벤트 급증 원인 확인
  - 최근 배포 내역과 prompt/rule 변경점 롤백 검토
- `CopilotActionApplySuccessRateLow`:
  - 프론트 액션 payload 스키마와 API 파라미터 드리프트 확인
  - `ai_widget_action_apply` 이벤트의 `action_apply_success` 누락 여부 점검
- `ChatSearchHandoffEmptyRatioHigh` 또는 `ChatSearchHandoffConfidenceLow`:
  - `chat_search_handoff_total{result=*}` 및 `chat_search_handoff_confidence` 추이 확인
  - `session_id`/`user_id` 컨텍스트 누락 여부 점검 (위젯 요청 payload)
  - `chat_search_handoff_profile_applied_total` 급감 시 Redis 선호 프로파일 hit율과 만료(`chat_pref_profile_total{status=miss}`) 확인

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
