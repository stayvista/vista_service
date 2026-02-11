#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:21434}"
CHAT_MODEL="${OLLAMA_CHAT_MODEL:-llama3.1:8b-instruct}"
EMBED_MODEL="${OLLAMA_EMBED_MODEL:-bge-m3}"

echo "[llm] readyz -> checking model tags"
RAW="$(curl -fsS "$BASE_URL/api/tags")"

if ! echo "$RAW" | grep -q "\"name\":\"$CHAT_MODEL"; then
  echo "not ready: missing chat model $CHAT_MODEL"
  exit 1
fi

if ! echo "$RAW" | grep -q "\"name\":\"$EMBED_MODEL"; then
  echo "not ready: missing embed model $EMBED_MODEL"
  exit 1
fi

echo "ready"
