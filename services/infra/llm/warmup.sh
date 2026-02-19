#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:23434}"
CHAT_MODEL="${OLLAMA_CHAT_MODEL:-llama3.1:8b}"

measure_ms() {
  local start end
  start=$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)
  curl -fsS "$BASE_URL/api/generate" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"$CHAT_MODEL\",\"prompt\":\"Say warmup ok\",\"stream\":false}" >/dev/null
  end=$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)
  echo $((end - start))
}

echo "[llm] measuring cold start latency"
COLD_MS="$(measure_ms)"
echo "[llm] cold latency: ${COLD_MS}ms"

echo "[llm] measuring warm latency"
WARM_MS="$(measure_ms)"
echo "[llm] warm latency: ${WARM_MS}ms"

if [ "$WARM_MS" -lt "$COLD_MS" ]; then
  echo "[llm] warmup improvement detected"
else
  echo "[llm] no improvement detected (depends on host/GPU state)"
fi
