#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/compose.yaml"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-stayvista-infra}"
CHAT_MODEL="${1:-llama3.1:8b-instruct}"
EMBED_MODEL="${2:-bge-m3}"
PREV_CHAT_MODEL="${OLLAMA_PREV_CHAT_MODEL:-llama3.1:8b-instruct}"

echo "[llm] pull chat model: $CHAT_MODEL"
OLLAMA_CHAT_MODEL="$CHAT_MODEL" OLLAMA_EMBED_MODEL="$EMBED_MODEL" \
  docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" --profile llm run --rm llm-init

echo "[llm] verify readiness with candidate models"
OLLAMA_CHAT_MODEL="$CHAT_MODEL" OLLAMA_EMBED_MODEL="$EMBED_MODEL" \
  "$ROOT_DIR/services/infra/llm/readyz.sh"

echo "[llm] warmup candidate"
OLLAMA_CHAT_MODEL="$CHAT_MODEL" "$ROOT_DIR/services/infra/llm/warmup.sh"

echo "[llm] run canary"
OLLAMA_CHAT_MODEL="$CHAT_MODEL" "$ROOT_DIR/services/infra/llm/canary.sh" "$CHAT_MODEL"

echo "[llm] model swap candidate verified"
echo "[llm] remember to update app env:"
echo "  CHAT_LLM_ACTIVE_MODEL=$CHAT_MODEL"
echo "  CHAT_EMBED_ACTIVE_MODEL=$EMBED_MODEL"
echo "[llm] rollback quick command (within 1 minute):"
echo "  OLLAMA_PREV_CHAT_MODEL=$CHAT_MODEL $ROOT_DIR/services/infra/llm/swap-model.sh $PREV_CHAT_MODEL $EMBED_MODEL"
