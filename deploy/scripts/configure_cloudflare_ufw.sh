#!/usr/bin/env bash
set -euo pipefail

CF_IPV4_URL="${CF_IPV4_URL:-https://www.cloudflare.com/ips-v4}"
CF_IPV6_URL="${CF_IPV6_URL:-https://www.cloudflare.com/ips-v6}"
WORK_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

if ! command -v curl >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y curl ca-certificates
fi

curl -fsSL "${CF_IPV4_URL}" -o "${WORK_DIR}/ips-v4"
curl -fsSL "${CF_IPV6_URL}" -o "${WORK_DIR}/ips-v6"

sudo ufw allow OpenSSH
sudo ufw default deny incoming
sudo ufw default allow outgoing

while IFS= read -r cidr; do
  [[ -z "${cidr}" ]] && continue
  sudo ufw allow proto tcp from "${cidr}" to any port 80
  sudo ufw allow proto tcp from "${cidr}" to any port 443
done < "${WORK_DIR}/ips-v4"

while IFS= read -r cidr; do
  [[ -z "${cidr}" ]] && continue
  sudo ufw allow proto tcp from "${cidr}" to any port 80
  sudo ufw allow proto tcp from "${cidr}" to any port 443
done < "${WORK_DIR}/ips-v6"

sudo ufw --force enable
sudo ufw status verbose

