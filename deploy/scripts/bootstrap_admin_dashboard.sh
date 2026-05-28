#!/usr/bin/env bash
set -euo pipefail

ADMIN_DASHBOARD_ROOT="${ADMIN_DASHBOARD_ROOT:-/opt/trading/admin-dashboard}"
ADMIN_DASHBOARD_PORT="${ADMIN_DASHBOARD_PORT:-18081}"
BOOTSTRAP_RELEASE="${ADMIN_DASHBOARD_ROOT}/releases/bootstrap"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root. Example: sudo ADMIN_DASHBOARD_ROOT=${ADMIN_DASHBOARD_ROOT} $0"
  exit 1
fi

if [ "${ADMIN_DASHBOARD_ROOT}" != "/opt/trading/admin-dashboard" ]; then
  echo "Refusing unexpected ADMIN_DASHBOARD_ROOT=${ADMIN_DASHBOARD_ROOT}"
  exit 1
fi

install -d -o trading -g trading -m 0755 "${ADMIN_DASHBOARD_ROOT}/releases"

if [ ! -e "${ADMIN_DASHBOARD_ROOT}/current" ]; then
  install -d -o trading -g trading -m 0755 "${BOOTSTRAP_RELEASE}"
  cat > "${BOOTSTRAP_RELEASE}/index.html" <<'HTML'
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <title>Trading Admin Dashboard</title>
  </head>
  <body>
    <p>Admin Dashboard deployment target is ready.</p>
  </body>
</html>
HTML
  chown trading:trading "${BOOTSTRAP_RELEASE}/index.html"
  ln -sfn "${BOOTSTRAP_RELEASE}" "${ADMIN_DASHBOARD_ROOT}/current"
fi

if command -v ufw >/dev/null 2>&1; then
  ufw allow in on tailscale0 to any port "${ADMIN_DASHBOARD_PORT}" proto tcp comment 'trading admin dashboard'
fi

echo "Admin Dashboard root is ready: ${ADMIN_DASHBOARD_ROOT}"
echo "Copy deploy/caddy/Caddyfile.admin-dashboard.example into /etc/caddy/Caddyfile and reload Caddy."
