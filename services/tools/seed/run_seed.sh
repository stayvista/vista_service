#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-minimal}"
SQL_FILE="$(dirname "$0")/seed_local.sql"
if [[ "$MODE" == "bulk" ]]; then
  SQL_FILE="$(dirname "$0")/seed_bulk.sql"
fi

mysql -h 127.0.0.1 -P 23306 -u root -proot stayvista < "$SQL_FILE"

echo "seed completed: $MODE"
