#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/etc/trading/trading.env}"
if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

: "${BACKUP_DIR:?BACKUP_DIR is required}"
: "${SPRING_DATASOURCE_URL:?SPRING_DATASOURCE_URL is required}"
: "${SPRING_DATASOURCE_USERNAME:?SPRING_DATASOURCE_USERNAME is required}"
: "${SPRING_DATASOURCE_PASSWORD:?SPRING_DATASOURCE_PASSWORD is required}"

DB_NAME="$(sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*$#\1#' <<<"${SPRING_DATASOURCE_URL}")"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
CONFIG_DIR="${BACKUP_CONFIG_DIR:-/etc/trading}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
TARGET_DIR="${BACKUP_DIR}/${TIMESTAMP}"
SQL_FILE="${TARGET_DIR}/${DB_NAME}.sql.gz"
CONFIG_ARCHIVE="${TARGET_DIR}/config.tar.gz"

umask 077
mkdir -p "${TARGET_DIR}"

MYSQL_PWD="${SPRING_DATASOURCE_PASSWORD}" mysqldump \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --user="${SPRING_DATASOURCE_USERNAME}" \
  --single-transaction \
  --quick \
  --routines \
  --triggers \
  "${DB_NAME}" | gzip -9 > "${SQL_FILE}"

tar --ignore-failed-read -czf "${CONFIG_ARCHIVE}" "${CONFIG_DIR}" /etc/caddy/Caddyfile /etc/systemd/system/trading.service /etc/mysql/mysql.conf.d/99-trading.cnf
sha256sum "${SQL_FILE}" "${CONFIG_ARCHIVE}" > "${TARGET_DIR}/SHA256SUMS"
find "${BACKUP_DIR}" -mindepth 1 -maxdepth 1 -type d -mtime +"${KEEP_DAYS}" -exec rm -rf {} +

echo "${TARGET_DIR}"
