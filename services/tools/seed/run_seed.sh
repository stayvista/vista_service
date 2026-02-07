#!/usr/bin/env bash
set -euo pipefail

mysql -h 127.0.0.1 -P 13306 -u root -proot stayvista < "$(dirname "$0")/seed_local.sql"
