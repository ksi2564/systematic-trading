#!/usr/bin/env bash
set -Eeuo pipefail

readonly archive_path="${1:-}"
readonly release_sha="${2:-}"
readonly expected_archive_sha="${3:-}"
readonly deploy_root="/opt/wallant"
readonly config_root="/etc/wallant"
readonly data_root="/var/lib/wallant"
readonly log_root="/var/log/wallant"
readonly release_root="${deploy_root}/releases"
readonly release_dir="${release_root}/${release_sha}"
readonly incoming_dir="${release_dir}.incoming"
readonly current_link="${deploy_root}/current"
readonly env_file="${config_root}/v2.env"
readonly mysql_env_file="${config_root}/mysql.env"
readonly systemd_unit="/etc/systemd/system/wallant-v2-api.service"

previous_target=""
current_switched=false
service_was_enabled=false

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

rollback_on_error() {
  local exit_code=$?
  trap - ERR

  if [[ "${current_switched}" == true ]]; then
    if [[ -n "${previous_target}" && -d "${previous_target}" ]]; then
      ln -sfn "${previous_target}" "${deploy_root}/current.rollback"
      mv -Tf "${deploy_root}/current.rollback" "${current_link}"
      if [[ -f "${previous_target}/v2/infra/systemd/wallant-v2-api.service" ]]; then
        install -o root -g root -m 0644 \
          "${previous_target}/v2/infra/systemd/wallant-v2-api.service" "${systemd_unit}"
        systemctl daemon-reload || true
      fi
      systemctl restart wallant-v2-api.service || true
      printf 'Restored previous application release: %s\n' "${previous_target}" >&2
    else
      systemctl stop wallant-v2-api.service || true
      if [[ "${service_was_enabled}" != true ]]; then
        systemctl disable wallant-v2-api.service || true
      fi
      printf 'Stopped the failed first deployment. MySQL data was retained.\n' >&2
    fi
  fi

  exit "${exit_code}"
}
trap rollback_on_error ERR

[[ "${EUID}" -eq 0 ]] || fail "Run this script as root."
[[ "${archive_path}" == /tmp/wallant-v2-*.tgz ]] || fail "Archive must be a v2 package under /tmp."
[[ -f "${archive_path}" ]] || fail "Archive not found: ${archive_path}"
[[ "${release_sha}" =~ ^[0-9a-f]{40}$ ]] || fail "Release SHA must be a full Git commit SHA."
[[ "${expected_archive_sha}" =~ ^[0-9a-f]{64}$ ]] || fail "Expected archive checksum is invalid."

actual_archive_sha="$(sha256sum "${archive_path}" | awk '{print $1}')"
[[ "${actual_archive_sha}" == "${expected_archive_sha}" ]] \
  || fail "Archive checksum mismatch. expected=${expected_archive_sha} actual=${actual_archive_sha}"

while IFS= read -r archive_entry; do
  case "${archive_entry}" in
    /*|../*|*/../*) fail "Unsafe archive path: ${archive_entry}" ;;
  esac
done < <(tar -tzf "${archive_path}")

for command_name in python3 docker systemctl curl openssl ss tar sha256sum runuser; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "Missing required command: ${command_name}"
done
python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' \
  || fail "Python 3.12 or newer is required."
docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin is required."
docker info >/dev/null 2>&1 || fail "Docker daemon is not available."

if systemctl is-enabled --quiet wallant-v2-api.service 2>/dev/null; then
  service_was_enabled=true
fi
if [[ -L "${current_link}" ]]; then
  previous_target="$(readlink -f "${current_link}")"
fi

getent group wallant >/dev/null 2>&1 || groupadd --system wallant
id -u wallant >/dev/null 2>&1 \
  || useradd --system --gid wallant --home-dir "${data_root}" --shell /usr/sbin/nologin wallant

install -d -o root -g root -m 0755 "${deploy_root}" "${release_root}"
install -d -o wallant -g wallant -m 0750 "${data_root}" "${data_root}/market" "${log_root}"
install -d -o root -g wallant -m 0750 "${config_root}"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
if [[ -e "${incoming_dir}" ]]; then
  mv "${incoming_dir}" "${incoming_dir}.${timestamp}.stale"
fi
install -d -o root -g root -m 0755 "${incoming_dir}"
tar -xzf "${archive_path}" -C "${incoming_dir}"

[[ -f "${incoming_dir}/v2/backend/pyproject.toml" ]] || fail "Backend package is missing."
[[ -f "${incoming_dir}/v2/frontend/dist/index.html" ]] || fail "Frontend build is missing."
[[ -f "${incoming_dir}/v2/infra/compose.yaml" ]] || fail "Compose file is missing."
[[ -f "${incoming_dir}/v2/infra/systemd/wallant-v2-api.service" ]] || fail "systemd unit is missing."

python3 -m venv "${incoming_dir}/venv"
"${incoming_dir}/venv/bin/python" -m pip install --disable-pip-version-check \
  "${incoming_dir}/v2/backend"
chown -R root:wallant "${incoming_dir}"
chmod -R o-w "${incoming_dir}"

if [[ ! -f "${mysql_env_file}" ]]; then
  mysql_password="$(openssl rand -hex 32)"
  mysql_root_password="$(openssl rand -hex 32)"
  install -o root -g root -m 0600 /dev/null "${mysql_env_file}"
  {
    printf 'WALLANT_MYSQL_PASSWORD=%s\n' "${mysql_password}"
    printf 'WALLANT_MYSQL_ROOT_PASSWORD=%s\n' "${mysql_root_password}"
  } >"${mysql_env_file}"
fi

if [[ ! -f "${env_file}" ]]; then
  # shellcheck disable=SC1090
  source "${mysql_env_file}"
  credential_master_key="$(openssl rand -base64 32 | tr '+/' '-_')"
  install -o root -g wallant -m 0640 /dev/null "${env_file}"
  {
    printf 'WALLANT_ENVIRONMENT=staging\n'
    printf 'WALLANT_DATABASE_URL=mysql+pymysql://wallant:%s@127.0.0.1:3307/wallant_v2\n' \
      "${WALLANT_MYSQL_PASSWORD}"
    printf 'WALLANT_PARQUET_ROOT=/var/lib/wallant/market\n'
    printf 'WALLANT_EXECUTION_ENABLED=false\n'
    printf 'WALLANT_BROKER_ADAPTER=disabled\n'
    printf 'WALLANT_CREDENTIAL_MASTER_KEY=%s\n' "${credential_master_key}"
    printf 'WALLANT_ALLOWED_ACCESS_EMAILS=[]\n'
    printf 'WALLANT_DISCORD_ENABLED=false\n'
    printf 'WALLANT_DISCORD_ALLOWED_USER_IDS=[]\n'
  } >"${env_file}"
fi

grep -Fx 'WALLANT_EXECUTION_ENABLED=false' "${env_file}" >/dev/null \
  || fail "Execution must remain disabled in ${env_file}."
grep -Fx 'WALLANT_BROKER_ADAPTER=disabled' "${env_file}" >/dev/null \
  || fail "Broker adapter must remain disabled in ${env_file}."

docker compose \
  --project-name wallant-v2 \
  --env-file "${mysql_env_file}" \
  --file "${incoming_dir}/v2/infra/compose.yaml" \
  up -d mysql

mysql_container_id="$(docker compose \
  --project-name wallant-v2 \
  --env-file "${mysql_env_file}" \
  --file "${incoming_dir}/v2/infra/compose.yaml" \
  ps -q mysql)"
[[ -n "${mysql_container_id}" ]] || fail "MySQL container was not created."

for attempt in $(seq 1 60); do
  health_status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${mysql_container_id}")"
  if [[ "${health_status}" == healthy ]]; then
    break
  fi
  if [[ "${attempt}" -eq 60 ]]; then
    fail "MySQL did not become healthy within 120 seconds; status=${health_status}."
  fi
  sleep 2
done

database_url="$(sed -n 's/^WALLANT_DATABASE_URL=//p' "${env_file}")"
[[ -n "${database_url}" ]] || fail "WALLANT_DATABASE_URL is missing."
# $1 and $2 are positional parameters in the child shell.
# shellcheck disable=SC2016
runuser -u wallant -- env WALLANT_DATABASE_URL="${database_url}" \
  bash -c 'cd "$1" && "$2" -c alembic.ini upgrade head' \
  wallant-migrate "${incoming_dir}/v2/backend" "${incoming_dir}/venv/bin/alembic"

if [[ -e "${release_dir}" ]]; then
  mv "${release_dir}" "${release_dir}.${timestamp}.replaced"
fi
mv "${incoming_dir}" "${release_dir}"
ln -sfn "${release_dir}" "${deploy_root}/current.next"
mv -Tf "${deploy_root}/current.next" "${current_link}"
current_switched=true

install -o root -g root -m 0644 \
  "${release_dir}/v2/infra/systemd/wallant-v2-api.service" "${systemd_unit}"
systemctl daemon-reload
systemctl enable --now wallant-v2-api.service
systemctl restart wallant-v2-api.service

health_payload=""
for attempt in $(seq 1 30); do
  if health_payload="$(curl -fsS http://127.0.0.1:8000/health)"; then
    break
  fi
  if [[ "${attempt}" -eq 30 ]]; then
    fail "v2 API did not become healthy within 60 seconds."
  fi
  sleep 2
done

printf '%s' "${health_payload}" | "${release_dir}/venv/bin/python" -c '
import json, sys
payload = json.load(sys.stdin)
if payload.get("status") != "UP":
    raise SystemExit("health status is not UP")
if payload.get("execution_enabled") is not False:
    raise SystemExit("execution is not disabled")
if payload.get("broker_adapter") != "disabled":
    raise SystemExit("broker adapter is not disabled")
'
systemctl is-active --quiet wallant-v2-api.service

printf 'DEPLOYED_RELEASE=%s\n' "$(readlink -f "${current_link}")"
printf 'API_HEALTH=%s\n' "${health_payload}"
printf 'EXECUTION_SAFETY=disabled\n'
