#!/usr/bin/env bash
set -Eeuo pipefail

readonly token_source="${1:-}"
readonly unit_source="${2:-}"
readonly config_root="/etc/wallant"
readonly token_file="${config_root}/cloudflared.token"
readonly unit_file="/etc/systemd/system/wallant-v2-cloudflared.service"

backup_dir=""
token_existed=false
unit_existed=false
changes_started=false

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  rollback_on_error 1
}

rollback_on_error() {
  local exit_code="${1:-$?}"
  trap - ERR

  if [[ "${changes_started}" == true ]]; then
    systemctl stop wallant-v2-cloudflared.service || true
    if [[ "${token_existed}" == true ]]; then
      cp -a "${backup_dir}/cloudflared.token" "${token_file}"
    else
      rm -f "${token_file}"
    fi
    if [[ "${unit_existed}" == true ]]; then
      cp -a "${backup_dir}/wallant-v2-cloudflared.service" "${unit_file}"
      systemctl daemon-reload || true
      systemctl restart wallant-v2-cloudflared.service || true
    else
      rm -f "${unit_file}"
      systemctl daemon-reload || true
      systemctl disable wallant-v2-cloudflared.service || true
    fi
    printf 'Cloudflare Tunnel service configuration was rolled back.\n' >&2
  fi
  exit "${exit_code}"
}
trap 'rollback_on_error $?' ERR

[[ "${EUID}" -eq 0 ]] || fail "Run this script as root."
[[ -r "${token_source}" ]] || fail "Tunnel token file is missing."
[[ -r "${unit_source}" ]] || fail "Tunnel systemd unit is missing."
[[ -r /etc/os-release ]] || fail "/etc/os-release is missing."

# shellcheck disable=SC1091
source /etc/os-release
[[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == 24.04 ]] \
  || fail "Tunnel install is restricted to Ubuntu 24.04; found ${ID:-unknown} ${VERSION_ID:-unknown}."

for command_name in apt-get curl dpkg getent groupadd id install mktemp mv python3 rm systemctl useradd; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "Missing required command: ${command_name}"
done

getent group wallant >/dev/null 2>&1 || fail "Required wallant group is missing."

python3 - "${token_source}" <<'PY'
import sys

token = open(sys.argv[1], encoding="utf-8").read().strip()
if len(token) < 100 or not token.startswith("eyJ") or any(char.isspace() for char in token):
    raise SystemExit("Tunnel token is malformed.")
PY

if ! command -v cloudflared >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  export NEEDRESTART_MODE=l
  install -d -o root -g root -m 0755 /usr/share/keyrings
  key_tmp="$(mktemp)"
  curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg -o "${key_tmp}"
  install -o root -g root -m 0644 "${key_tmp}" /usr/share/keyrings/cloudflare-main.gpg
  rm -f "${key_tmp}"
  printf '%s\n' \
    'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared noble main' \
    >/etc/apt/sources.list.d/cloudflared.list
  apt-get update
  apt-get install -y --no-install-recommends cloudflared
fi

cloudflared_version="$(cloudflared --version | awk '{print $3}' | head -1)"
dpkg --compare-versions "${cloudflared_version}" ge 2025.4.0 \
  || fail "cloudflared 2025.4.0 or newer is required for token-file; found ${cloudflared_version}."

getent group cloudflared >/dev/null 2>&1 || groupadd --system cloudflared
id -u cloudflared >/dev/null 2>&1 \
  || useradd --system --gid cloudflared --home-dir /nonexistent --shell /usr/sbin/nologin cloudflared

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="/var/backups/wallant/v2-access/${timestamp}-tunnel"
install -d -o root -g root -m 0700 "${backup_dir}"
if [[ -f "${token_file}" ]]; then
  token_existed=true
  cp -a "${token_file}" "${backup_dir}/cloudflared.token"
fi
if [[ -f "${unit_file}" ]]; then
  unit_existed=true
  cp -a "${unit_file}" "${backup_dir}/wallant-v2-cloudflared.service"
fi
changes_started=true

install -d -o root -g wallant -m 0750 "${config_root}"
install -o root -g root -m 0600 "${token_source}" "${token_file}.next"
mv -f "${token_file}.next" "${token_file}"
install -o root -g root -m 0644 "${unit_source}" "${unit_file}"
grep -F 'LoadCredential=tunnel-token:/etc/wallant/cloudflared.token' "${unit_file}" >/dev/null \
  || fail "Tunnel service must load the protected systemd credential."
grep -F -- '--token-file %d/tunnel-token' "${unit_file}" >/dev/null \
  || fail "Tunnel service must consume the injected systemd credential."

systemctl daemon-reload
systemctl enable wallant-v2-cloudflared.service
systemctl restart wallant-v2-cloudflared.service

for attempt in $(seq 1 15); do
  if systemctl is-active --quiet wallant-v2-cloudflared.service; then
    break
  fi
  if [[ "${attempt}" -eq 15 ]]; then
    systemctl status wallant-v2-cloudflared.service --no-pager || true
    journalctl -u wallant-v2-cloudflared.service -n 60 --no-pager || true
    fail "Cloudflare Tunnel service did not become active."
  fi
  sleep 2
done

printf 'CLOUDFLARED_VERSION=%s\n' "${cloudflared_version}"
printf 'TUNNEL_SERVICE=active\n'
printf 'TUNNEL_TOKEN_STORAGE=systemd-credential\n'
