#!/usr/bin/env bash
set -euo pipefail

(cd services/docker && docker compose up -d)

cat <<MSG
infra is up.
- mysql: 127.0.0.1:13306
- redis: 127.0.0.1:16379
- kafka: 127.0.0.1:19092
- opensearch: 127.0.0.1:9200
MSG
