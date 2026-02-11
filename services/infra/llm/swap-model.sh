#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/compose.yaml"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-stayvista-infra}"
CHAT_MODEL="${1:-llama3.1:8b-instruct}"
EMBED_MODEL="${2:-bge-m3}"

echo "[llm] pull chat model: $CHAT_MODEL"
OLLAMA_CHAT_MODEL="$CHAT_MODEL" OLLAMA_EMBED_MODEL="$EMBED_MODEL" \
  docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" --profile llm run --rm llm-init

echo "[llm] model swap complete"
echo "[llm] remember to update app env:"
echo "  CHAT_LLM_ACTIVE_MODEL=$CHAT_MODEL"
echo "  CHAT_EMBED_ACTIVE_MODEL=$EMBED_MODEL"
