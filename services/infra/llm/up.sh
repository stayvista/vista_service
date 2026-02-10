#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/services/docker/docker-compose.yml"

echo "[llm] starting ollama service"
docker compose -f "$COMPOSE_FILE" up -d llm

echo "[llm] waiting for health endpoint"
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:11434/api/tags" >/dev/null; then
    echo "[llm] healthy"
    break
  fi
  sleep 1
done

echo "[llm] pulling default models"
docker compose -f "$COMPOSE_FILE" run --rm llm-init

echo "[llm] done"
