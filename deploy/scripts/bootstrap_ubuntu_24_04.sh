#!/usr/bin/env bash
set -euo pipefail

sudo apt-get update
sudo apt-get install -y openjdk-21-jre-headless mysql-server caddy rsync unzip

sudo useradd --system --home /opt/trading --shell /usr/sbin/nologin trading || true
sudo install -d -o trading -g trading /opt/trading/app
sudo install -d -o trading -g trading /var/log/trading
sudo install -d -o root -g root /etc/trading
sudo install -d -o root -g root /var/backups/trading

echo "copy deploy/mysql/99-trading.cnf -> /etc/mysql/mysql.conf.d/99-trading.cnf"
echo "copy deploy/caddy/Caddyfile.example -> /etc/caddy/Caddyfile"
echo "copy deploy/systemd/trading.service -> /etc/systemd/system/trading.service"
echo "copy deploy/logrotate/trading -> /etc/logrotate.d/trading"
echo "copy deploy/env/trading.env.example -> /etc/trading/trading.env and fill secrets"
