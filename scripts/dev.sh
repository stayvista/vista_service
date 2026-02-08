#!/usr/bin/env bash
set -euo pipefail

docker compose -p stayvista-infra -f compose.yaml up -d --remove-orphans

cat <<MSG
infra is up.
- mysql: 127.0.0.1:23306
- redis: 127.0.0.1:26379
- kafka: 127.0.0.1:39092,39093,39094
- opensearch: 127.0.0.1:39200
MSG
