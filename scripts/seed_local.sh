#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SQL_FILE="${ROOT_DIR}/scripts/seed_local.sql"
COMPOSE_FILE="${ROOT_DIR}/compose.yaml"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-stayvista-infra}"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-23306}"
DB_NAME="${DB_NAME:-stayvista}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-root}"
API_BASE="${API_BASE:-http://localhost:18765}"
ADMIN_ID="${ADMIN_ID:-1}"
SEED_PROPERTY_COUNT="${SEED_PROPERTY_COUNT:-20000}"
REINDEX_AFTER_SEED="${REINDEX_AFTER_SEED:-true}"

run_seed_with_local_mysql() {
  mysql \
    --default-character-set=utf8mb4 \
    --host="${DB_HOST}" \
    --port="${DB_PORT}" \
    --user="${DB_USERNAME}" \
    --password="${DB_PASSWORD}" \
    "${DB_NAME}" < "${SQL_FILE}"
}

run_seed_with_docker_mysql() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "[seed] docker is not available; cannot use docker mysql fallback."
    return 1
  fi
  if ! docker compose -p "${COMPOSE_PROJECT}" -f "${COMPOSE_FILE}" ps mysql >/dev/null 2>&1; then
    echo "[seed] mysql service is not running in docker compose (${COMPOSE_PROJECT})."
    return 1
  fi
  docker compose -p "${COMPOSE_PROJECT}" -f "${COMPOSE_FILE}" exec -T mysql \
    mysql \
    --default-character-set=utf8mb4 \
    --host=127.0.0.1 \
    --port=3306 \
    --user="${DB_USERNAME}" \
    --password="${DB_PASSWORD}" \
    "${DB_NAME}" < "${SQL_FILE}"
}

echo "[seed] loading SQL data into ${DB_USERNAME}@${DB_HOST}:${DB_PORT}/${DB_NAME}"

if command -v mysql >/dev/null 2>&1; then
  set +e
  seed_output="$(run_seed_with_local_mysql 2>&1)"
  seed_exit=$?
  set -e
  if [[ ${seed_exit} -eq 0 ]]; then
    echo "${seed_output}"
  else
    echo "${seed_output}"
    should_try_docker_fallback=false
    if [[ "${seed_output}" == *"mysql_native_password"* ]]; then
      should_try_docker_fallback=true
      echo "[seed] local mysql client auth plugin mismatch detected; retrying via docker mysql client..."
    elif [[ ("${DB_HOST}" == "127.0.0.1" || "${DB_HOST}" == "localhost") && "${DB_PORT}" == "23306" ]]; then
      should_try_docker_fallback=true
      echo "[seed] local mysql client failed against default local DB target; retrying via docker mysql client..."
    fi

    if [[ "${should_try_docker_fallback}" == "true" ]]; then
      run_seed_with_docker_mysql
    else
      exit ${seed_exit}
    fi
  fi
else
  echo "[seed] local mysql client not found; trying docker mysql client..."
  run_seed_with_docker_mysql
fi

echo "[seed] SQL seed completed."

if [[ "${REINDEX_AFTER_SEED}" == "true" ]]; then
  if command -v curl >/dev/null 2>&1; then
    reindex_url="${API_BASE}/v1/admin/search/reindex?limit=${SEED_PROPERTY_COUNT}"
    echo "[seed] triggering search reindex via ${reindex_url} (X-Admin-Id=${ADMIN_ID})"
    if ! curl -fsS -X POST -H "X-Admin-Id: ${ADMIN_ID}" "${reindex_url}" >/dev/null; then
      echo "[seed] warning: reindex call failed. ensure app is running, OpenSearch is reachable, and ADMIN_ID is numeric."
    else
      echo "[seed] reindex request submitted."
    fi
  else
    echo "[seed] curl is not installed; skipping reindex API call."
  fi
fi
