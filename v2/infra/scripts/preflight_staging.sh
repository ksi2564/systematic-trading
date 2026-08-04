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

printf 'HOST\n'
hostnamectl 2>/dev/null || hostname
uname -a
printf '\nCAPACITY\n'
free -h
swapon --show || true
df -h / /opt 2>/dev/null || df -h /

printf '\nREQUIRED COMMANDS\n'
for command_name in python3 docker systemctl curl openssl ss swapon tar sha256sum; do
  require_command "${command_name}"
done

if command -v python3 >/dev/null 2>&1; then
  if python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)'; then
    printf 'OK Python: %s\n' "$(python3 --version 2>&1)"
  else
    printf 'Python 3.12 or newer is required; found %s\n' "$(python3 --version 2>&1)" >&2
    failures=$((failures + 1))
  fi
fi

if command -v docker >/dev/null 2>&1; then
  if docker compose version; then
    printf 'OK Docker Compose plugin\n'
  else
    printf 'Docker Compose plugin is required.\n' >&2
    failures=$((failures + 1))
  fi

  if docker info >/dev/null 2>&1; then
    printf 'OK Docker daemon\n'
  else
    printf 'Docker daemon is not available.\n' >&2
    failures=$((failures + 1))
  fi
fi

memory_total_kib="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
swap_total_kib="$(awk '/^SwapTotal:/ {print $2}' /proc/meminfo)"
if ((memory_total_kib < 4 * 1024 * 1024 && swap_total_kib < 1024 * 1024)); then
  printf 'Hosts with less than 4 GiB RAM require at least 1 GiB swap; found %d KiB.\n' \
    "${swap_total_kib}" >&2
  failures=$((failures + 1))
else
  printf 'OK memory safety: RAM=%d KiB swap=%d KiB\n' "${memory_total_kib}" "${swap_total_kib}"
fi

printf '\nSERVICE STATUS\n'
for unit_name in trading caddy docker wallant-v2-api; do
  printf '%-18s %s\n' "${unit_name}" "$(systemctl is-active "${unit_name}" 2>/dev/null || true)"
done

printf '\nDEPLOYMENT PATHS\n'
for path_name in /opt/trading /opt/wallant /etc/wallant /var/lib/wallant; do
  if [[ -e "${path_name}" ]]; then
    printf 'EXISTS  %s\n' "${path_name}"
  else
    printf 'ABSENT  %s\n' "${path_name}"
  fi
done

printf '\nRELEVANT LISTENERS\n'
ss -lntp 2>/dev/null | awk 'NR == 1 || $4 ~ /:(8000|8080|3307|18081|18082)$/' || true

if ((failures > 0)); then
  printf '\nPreflight failed with %d missing requirement(s).\n' "${failures}" >&2
  exit 1
fi

printf '\nPreflight passed. No files, services, firewall rules, or databases were changed.\n'
