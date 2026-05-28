#!/usr/bin/env bash
set -euo pipefail

sudo apt-get update
sudo apt-get install -y openjdk-21-jre-headless mysql-server caddy rsync unzip curl ca-certificates ufw

sudo useradd --system --home /opt/trading --shell /usr/sbin/nologin trading || true
sudo install -d -o trading -g trading /opt/trading/app
sudo install -d -o trading -g trading /opt/trading/admin-dashboard/releases
sudo install -d -o root -g root -m 0755 /opt/trading/bin
sudo install -d -o trading -g trading /var/log/trading
sudo install -d -o root -g root /etc/trading
sudo install -d -o root -g root /var/backups/trading
sudo timedatectl set-timezone Asia/Seoul

echo "copy deploy/mysql/99-trading.cnf -> /etc/mysql/mysql.conf.d/99-trading.cnf"
echo "copy deploy/caddy/Caddyfile.example -> /etc/caddy/Caddyfile"
echo "merge deploy/caddy/Caddyfile.admin-dashboard.example into /etc/caddy/Caddyfile"
echo "copy deploy/systemd/trading.service -> /etc/systemd/system/trading.service"
echo "copy deploy/systemd/trading-backup.service -> /etc/systemd/system/trading-backup.service"
echo "copy deploy/systemd/trading-backup.timer -> /etc/systemd/system/trading-backup.timer"
echo "copy deploy/logrotate/trading -> /etc/logrotate.d/trading"
echo "copy deploy/env/trading.env.example -> /etc/trading/trading.env and fill secrets"
echo "copy deploy/scripts/backup_mysql.sh -> /opt/trading/bin/backup_mysql.sh"
echo "copy deploy/scripts/sync_backup_to_mac.sh -> /opt/trading/bin/sync_backup_to_mac.sh"
echo "copy deploy/scripts/restore_mysql.sh -> /opt/trading/bin/restore_mysql.sh"
echo "run deploy/scripts/bootstrap_admin_dashboard.sh once after Tailscale is installed"
