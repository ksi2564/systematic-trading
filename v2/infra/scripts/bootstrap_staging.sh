#!/usr/bin/env bash
set -Eeuo pipefail

readonly swap_file="/swapfile-wallant-v2"
readonly swap_size_mib=2048
readonly sysctl_file="/etc/sysctl.d/99-wallant-v2-memory.conf"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ "${EUID}" -eq 0 ]] || fail "Run this script as root."
[[ -r /etc/os-release ]] || fail "/etc/os-release is missing."

# shellcheck disable=SC1091
source /etc/os-release
[[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == 24.04 ]] \
  || fail "This bootstrap is restricted to Ubuntu 24.04; found ${ID:-unknown} ${VERSION_ID:-unknown}."

for command_name in apt-cache apt-get awk chmod fallocate file free grep install mkswap swapon sysctl systemctl; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "Missing required command: ${command_name}"
done

if swapon --noheadings --show=NAME | grep -Fx "${swap_file}" >/dev/null; then
  printf 'Swap is already active: %s\n' "${swap_file}"
else
  if [[ -e "${swap_file}" ]]; then
    if ! file -b "${swap_file}" | grep -qi 'swap file'; then
      fail "Existing ${swap_file} is not a swap file; refusing to overwrite it."
    fi
  else
    fallocate -l "${swap_size_mib}M" "${swap_file}"
    chmod 0600 "${swap_file}"
    mkswap "${swap_file}"
  fi
  chmod 0600 "${swap_file}"
  swapon "${swap_file}"
fi

if ! grep -Eq '^/swapfile-wallant-v2[[:space:]]+none[[:space:]]+swap[[:space:]]' /etc/fstab; then
  printf '%s\n' '/swapfile-wallant-v2 none swap sw 0 0' >>/etc/fstab
fi

install -o root -g root -m 0644 /dev/null "${sysctl_file}"
printf '%s\n' 'vm.swappiness=10' >"${sysctl_file}"
sysctl -w vm.swappiness=10

export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=l
apt-get update
apt-cache show docker.io >/dev/null 2>&1 || fail "Ubuntu docker.io package is unavailable."
apt-cache show docker-compose-v2 >/dev/null 2>&1 \
  || fail "Ubuntu docker-compose-v2 package is unavailable."
apt-get install -y --no-install-recommends docker.io docker-compose-v2
systemctl enable --now docker.service

docker info >/dev/null
docker compose version
swapon --show
free -h
printf 'BOOTSTRAP_STATUS=ready\n'
