#!/usr/bin/env bash
set -euo pipefail

TAILSCALE_HOSTNAME="${TAILSCALE_HOSTNAME:-trading-vm}"

if ! command -v curl >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y curl ca-certificates
fi

curl -fsSL https://tailscale.com/install.sh | sh
sudo systemctl enable --now tailscaled

if [[ -n "${TAILSCALE_AUTH_KEY:-}" ]]; then
  sudo tailscale up --auth-key="${TAILSCALE_AUTH_KEY}" --hostname="${TAILSCALE_HOSTNAME}"
else
  echo "run: sudo tailscale up --hostname=${TAILSCALE_HOSTNAME}"
fi

