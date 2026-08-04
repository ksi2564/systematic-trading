#!/usr/bin/env bash
set -Eeuo pipefail

failures=0

require_command() {
  local command_name="$1"
  if command -v "${command_name}" >/dev/null 2>&1; then
    printf 'OK command: %s\n' "${command_name}"
  else
    printf 'MISSING command: %s\n' "${command_name}" >&2
    failures=$((failures + 1))
  fi
}

[[ "${EUID}" -eq 0 ]] || { printf 'Run this script as root.\n' >&2; exit 1; }

printf 'PRIVATE ACCESS PREREQUISITES\n'
for command_name in caddy curl grep python3 ss systemctl; do
  require_command "${command_name}"
done

for required_path in \
  /etc/caddy/Caddyfile \
  /etc/wallant/v2.env \
  /opt/wallant/current/v2/frontend/dist/index.html; do
  if [[ -e "${required_path}" ]]; then
    printf 'OK path: %s\n' "${required_path}"
  else
    printf 'MISSING path: %s\n' "${required_path}" >&2
    failures=$((failures + 1))
  fi
done

if command -v caddy >/dev/null 2>&1 && [[ -f /etc/caddy/Caddyfile ]]; then
  if caddy validate --config /etc/caddy/Caddyfile; then
    printf 'OK existing Caddy configuration\n'
  else
    printf 'Existing Caddy configuration is invalid.\n' >&2
    failures=$((failures + 1))
  fi
fi

printf '\nSERVICE STATUS\n'
for unit_name in trading caddy wallant-v2-api wallant-v2-cloudflared; do
  printf '%-28s %s\n' "${unit_name}" "$(systemctl is-active "${unit_name}" 2>/dev/null || true)"
done

printf '\nACCESS CONFIGURATION\n'
if grep -Fx 'WALLANT_ENVIRONMENT=production' /etc/wallant/v2.env >/dev/null 2>&1; then
  printf 'ENVIRONMENT=production\n'
else
  printf 'ENVIRONMENT=not-production\n'
fi
if python3 - /etc/wallant/v2.env <<'PY'
import json
import sys

value = ""
for line in open(sys.argv[1], encoding="utf-8"):
    if line.startswith("WALLANT_ALLOWED_ACCESS_EMAILS="):
        value = line.split("=", 1)[1].strip()
        break
try:
    emails = json.loads(value)
except (json.JSONDecodeError, TypeError):
    raise SystemExit(1)
raise SystemExit(0 if isinstance(emails, list) and bool(emails) else 1)
PY
then
  printf 'ALLOWED_ACCESS_EMAILS_CONFIGURED=yes\n'
else
  printf 'ALLOWED_ACCESS_EMAILS_CONFIGURED=no\n'
fi

if command -v cloudflared >/dev/null 2>&1; then
  printf 'CLOUDFLARED_VERSION=%s\n' "$(cloudflared --version | head -1)"
else
  printf 'CLOUDFLARED_VERSION=not-installed\n'
fi

printf '\nACCESS LISTENER\n'
ss -lntp 2>/dev/null | awk 'NR == 1 || $4 ~ /:18082$/' || true

if ((failures > 0)); then
  printf '\nPrivate access preflight failed with %d issue(s).\n' "${failures}" >&2
  exit 1
fi

printf '\nPrivate access preflight passed. No files or services were changed.\n'
