#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/compose.yaml"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-stayvista-infra}"
BASE_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:21434}"

if docker ps -a --format '{{.Names}}' | grep -qx 'stayvista-llm'; then
  echo "[llm] removing legacy container: stayvista-llm"
  docker rm -f stayvista-llm >/dev/null
fi

if docker ps -a --format '{{.Names}}' | grep -qx 'stayvista-llm-init'; then
  echo "[llm] removing legacy container: stayvista-llm-init"
  docker rm -f stayvista-llm-init >/dev/null
fi

echo "[llm] starting ollama service"
docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" --profile llm up -d llm

echo "[llm] waiting for health endpoint"
for i in $(seq 1 30); do
  if curl -fsS "$BASE_URL/api/tags" >/dev/null; then
    echo "[llm] healthy"
    break
  fi
  sleep 1
done

echo "[llm] pulling default models"
docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" --profile llm run --rm llm-init

echo "[llm] done"
