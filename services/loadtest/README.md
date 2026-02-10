# Loadtest (k6)

## Prerequisites
- k6 installed
- local API running on `http://localhost:18765`
- seed data prepared:
```bash
./services/tools/seed/run_seed.sh bulk
```
- 로그인 세션 토큰(쓰기 API 부하테스트 시 필요):
```bash
AUTH_TOKEN="$(curl -sS -X POST http://localhost:18765/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{\"email\":\"demo.user@stayvista.local\",\"password\":\"demo1234!\"}' | jq -r '.data.access_token')"
```

## Scenarios
1. Search steady
```bash
k6 run services/loadtest/k6/search.js
```
2. Booking hold spike
```bash
ROOM_TYPE_ID=1 CHECK_IN=2026-02-10 CHECK_OUT=2026-02-12 k6 run services/loadtest/k6/booking_hold.js
```
3. Full funnel
```bash
AUTH_TOKEN="$AUTH_TOKEN" ROOM_TYPE_ID=1 k6 run services/loadtest/k6/full_funnel.js
```
4. Chat (LLM 포함)
```bash
k6 run services/loadtest/k6/chat_recommend.js
```

## Key metrics
- `http_req_duration`, `http_req_failed`
- `hold_409_rate`, `hold_429_rate`, `hold_5xx_rate`
- `funnel_409_rate`, `funnel_429_rate`, `funnel_5xx_rate`
- `funnel_search_duration`, `funnel_hold_duration`, `funnel_confirm_duration`
- `chat_rules_p95`, `chat_llm_p95`, `chat_cache_p95`
- `chat_llm_used_rate`, `chat_req_failed`

## Example report export
```bash
k6 run services/loadtest/k6/booking_hold.js \
  --summary-export /tmp/booking_hold_summary.json
```

## Queue before/after comparison
- Queue OFF: `stayvista.queue.enabled=false`
- Queue ON: `stayvista.queue.enabled=true`
- Compare `http_req_duration`, `http_req_failed`, and 429 ratio.

## LLM SLO quick check (B-0410)
- LLM off(template/rules): `chat_rules_p95 < 300ms`
- LLM on: `chat_llm_p95 < 1200ms`
- hard timeout guard: app config `CHAT_LLM_HARD_TIMEOUT_MS=6000`
