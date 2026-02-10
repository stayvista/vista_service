#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"

echo "[llm] healthz -> $BASE_URL/api/tags"
curl -fsS "$BASE_URL/api/tags" >/dev/null
echo "ok"
