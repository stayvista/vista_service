#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SQL_FILE="${ROOT_DIR}/scripts/seed_local.sql"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-23306}"
DB_NAME="${DB_NAME:-stayvista}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-root}"
API_BASE="${API_BASE:-http://localhost:8080}"
SEED_PROPERTY_COUNT="${SEED_PROPERTY_COUNT:-10000}"
REINDEX_AFTER_SEED="${REINDEX_AFTER_SEED:-true}"

if ! command -v mysql >/dev/null 2>&1; then
  echo "mysql client is required but not found in PATH."
  exit 1
fi

echo "[seed] loading SQL data into ${DB_USERNAME}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
mysql \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --user="${DB_USERNAME}" \
  --password="${DB_PASSWORD}" \
  "${DB_NAME}" < "${SQL_FILE}"

echo "[seed] SQL seed completed."

if [[ "${REINDEX_AFTER_SEED}" == "true" ]]; then
  if command -v curl >/dev/null 2>&1; then
    echo "[seed] triggering search reindex via ${API_BASE}/v1/admin/search/reindex?limit=${SEED_PROPERTY_COUNT}"
    if ! curl -fsS -X POST "${API_BASE}/v1/admin/search/reindex?limit=${SEED_PROPERTY_COUNT}" >/dev/null; then
      echo "[seed] warning: reindex call failed. ensure app is running and OpenSearch is reachable."
    else
      echo "[seed] reindex request submitted."
    fi
  else
    echo "[seed] curl is not installed; skipping reindex API call."
  fi
fi
