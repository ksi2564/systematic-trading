#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <backup-sql.gz> [database_name]"
  exit 1
fi

ENV_FILE="${ENV_FILE:-/etc/trading/trading.env}"
if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

: "${SPRING_DATASOURCE_USERNAME:?SPRING_DATASOURCE_USERNAME is required}"
: "${SPRING_DATASOURCE_PASSWORD:?SPRING_DATASOURCE_PASSWORD is required}"

SQL_FILE="$1"
DB_NAME="${2:-$(sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*$#\1#' <<<"${SPRING_DATASOURCE_URL}")}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"

MYSQL_PWD="${SPRING_DATASOURCE_PASSWORD}" mysql \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --user="${SPRING_DATASOURCE_USERNAME}" \
  -e "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

gzip -dc "${SQL_FILE}" | MYSQL_PWD="${SPRING_DATASOURCE_PASSWORD}" mysql \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --user="${SPRING_DATASOURCE_USERNAME}" \
  "${DB_NAME}"
