#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/etc/trading/trading.env}"
if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

if [[ "${MAC_BACKUP_ENABLED:-false}" != "true" ]]; then
  echo "mac backup is disabled"
  exit 0
fi

: "${BACKUP_DIR:?BACKUP_DIR is required}"
: "${MAC_BACKUP_SSH_USER:?MAC_BACKUP_SSH_USER is required}"
: "${MAC_BACKUP_SSH_HOST:?MAC_BACKUP_SSH_HOST is required}"
: "${MAC_BACKUP_SSH_KEY:?MAC_BACKUP_SSH_KEY is required}"
: "${MAC_BACKUP_REMOTE_DIR:?MAC_BACKUP_REMOTE_DIR is required}"

SSH_PORT="${MAC_BACKUP_SSH_PORT:-22}"
LATEST_DIR="$(find "${BACKUP_DIR}" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"

if [[ -z "${LATEST_DIR}" ]]; then
  echo "no backup directory found"
  exit 1
fi

rsync -az \
  -e "ssh -i ${MAC_BACKUP_SSH_KEY} -p ${SSH_PORT}" \
  "${LATEST_DIR}/" \
  "${MAC_BACKUP_SSH_USER}@${MAC_BACKUP_SSH_HOST}:${MAC_BACKUP_REMOTE_DIR}/$(basename "${LATEST_DIR}")/"
