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
1. Search with filters (100 rps)
```bash
k6 run services/loadtest/k6/search.js
```
2. Price calendar steady (50 rps)
```bash
k6 run services/loadtest/k6/price_calendar.js \
  --summary-export /tmp/price_calendar_summary.json
```
3. Booking hold spike
```bash
ROOM_TYPE_ID=1 CHECK_IN=2026-02-10 CHECK_OUT=2026-02-12 k6 run services/loadtest/k6/booking_hold.js
```
4. Full funnel
```bash
AUTH_TOKEN="$AUTH_TOKEN" ROOM_TYPE_ID=1 k6 run services/loadtest/k6/full_funnel.js
```
5. Chat (LLM 포함)
```bash
k6 run services/loadtest/k6/chat_recommend.js
```
6. Chat stream SLO (steady + spike + cache-hit)
```bash
# LLM off 구간 검증 시
CHAT_LLM_ENABLED=false k6 run services/loadtest/k6/chat_stream_slo.js \
  --summary-export /tmp/chat_stream_slo_summary.json

# 기본(LLM on + stream)
k6 run services/loadtest/k6/chat_stream_slo.js \
  --summary-export /tmp/chat_stream_slo_summary.json
```
7. Nearby map (steady + drag + spike)
```bash
k6 run services/loadtest/k6/nearby.js \
  --summary-export /tmp/nearby_summary.json
```
8. Autocomplete (empty focus + typing mixed, typing 200 rps)
```bash
k6 run services/loadtest/k6/autocomplete.js \
  --summary-export /tmp/autocomplete_summary.json
```

## Key metrics
- `http_req_duration`, `http_req_failed`
- `hold_409_rate`, `hold_429_rate`, `hold_5xx_rate`
- `funnel_409_rate`, `funnel_429_rate`, `funnel_5xx_rate`
- `funnel_search_duration`, `funnel_hold_duration`, `funnel_confirm_duration`
- `chat_rules_p95`, `chat_llm_p95`, `chat_cache_p95`
- `chat_llm_used_rate`, `chat_req_failed`
- `chat_llm_off_p95`
- `chat_stream_ttfb_ms`, `chat_stream_complete_ms`, `chat_stream_failed`
- `nearby_req_duration_ms`, `nearby_429_rate`, `nearby_5xx_rate`, `nearby_error_rate`
- `ac_req_duration_ms`, `ac_cache_hit_rate`, `ac_429_rate`, `ac_bad_payload_total`
- `search_req_duration_ms`, `search_5xx_rate`, `search_filter_usage_total`
- `price_calendar_req_duration_ms`, `price_calendar_429_rate`, `price_calendar_latency_ms`
- `booking_funnel_stage_total`

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

## Chat stream SLO check (B-0459)
- LLM off: `chat_llm_off_p95 < 250ms`
- LLM on(stream): `chat_stream_ttfb_ms p95 < 500ms`
- LLM on(stream): `chat_stream_complete_ms p95 < 2000ms`
- hard timeout guard: app config `LLM_TIMEOUT_HARD_MS=5000` 또는 `CHAT_LLM_HARD_TIMEOUT_MS=5000`

## Dashboard
- Grafana import JSON: `services/loadtest/grafana/chat_slo_dashboard.json`
- Grafana import JSON: `services/loadtest/grafana/nearby_dashboard.json`
- Grafana import JSON: `services/loadtest/grafana/search_parity_dashboard.json`
