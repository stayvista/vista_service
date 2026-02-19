#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:23434}"
CHAT_MODEL="${1:-${OLLAMA_CHAT_MODEL:-llama3.1:8b}}"
RUNS="${OLLAMA_CANARY_RUNS:-5}"
P95_BUDGET_MS="${OLLAMA_CANARY_P95_BUDGET_MS:-4500}"

measure_once() {
  local start end body
  start="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
  body="$(curl -fsS --max-time 12 "$BASE_URL/api/generate" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"$CHAT_MODEL\",\"prompt\":\"canary probe: return ok\",\"stream\":false}")"
  end="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
  if ! echo "$body" | grep -q "\"response\""; then
    echo "canary failure: response field missing" >&2
    return 1
  fi
  echo $((end - start))
}

echo "[llm] canary start model=$CHAT_MODEL runs=$RUNS"
latencies=()
for _ in $(seq 1 "$RUNS"); do
  latencies+=("$(measure_once)")
done

p95="$(python3 - <<'PY' "${latencies[@]}"
import sys
vals = sorted(int(x) for x in sys.argv[1:])
idx = int((len(vals)-1) * 0.95)
print(vals[idx])
PY
)"
avg="$(python3 - <<'PY' "${latencies[@]}"
import sys
vals = [int(x) for x in sys.argv[1:]]
print(int(sum(vals) / max(1, len(vals))))
PY
)"

echo "[llm] canary latencies(ms): ${latencies[*]}"
echo "[llm] canary avg=${avg}ms p95=${p95}ms budget=${P95_BUDGET_MS}ms"

if [ "$p95" -gt "$P95_BUDGET_MS" ]; then
  echo "[llm] canary failed: p95 exceeds budget"
  exit 1
fi

echo "[llm] canary passed"
