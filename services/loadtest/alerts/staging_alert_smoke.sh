#!/usr/bin/env bash
set -euo pipefail

PROM_BASE_URL="${PROM_BASE_URL:-http://127.0.0.1:39090}"

query() {
  local expr="$1"
  curl -fsS --get "$PROM_BASE_URL/api/v1/query" --data-urlencode "query=$expr"
}

echo "[alerts] checking Prometheus readiness"
query "up" >/dev/null
echo "[alerts] prometheus reachable: $PROM_BASE_URL"

echo "[alerts] ChatErrorBurnRateFast expression"
query "(sum(rate(chat_llm_fail_total[5m])) / clamp_min(sum(rate(chat_requests_total[5m])), 1))"

echo
echo "[alerts] ChatErrorBurnRateSlow expression"
query "(sum(rate(chat_llm_fail_total[30m])) / clamp_min(sum(rate(chat_requests_total[30m])), 1))"

echo
echo "[alerts] ChatLatencyP95High expression"
query "max_over_time(chat_latency_seconds_max[5m])"

echo
echo "[alerts] active alerts"
curl -fsS "$PROM_BASE_URL/api/v1/alerts"

echo
echo "[alerts] smoke check completed"
echo "[alerts] To force staging test, run k6 spike and re-check:"
echo "  k6 run services/loadtest/k6/chat_stream_slo.js"
