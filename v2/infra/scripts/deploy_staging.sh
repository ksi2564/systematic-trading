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
readonly current_link="${deploy_root}/current"
readonly env_file="${config_root}/v2.env"
readonly mysql_env_file="${config_root}/mysql.env"
readonly systemd_unit="/etc/systemd/system/wallant-v2-api.service"
readonly retain_recent_releases=3
readonly deploy_lock_path="/run/lock/wallant-v2-deploy.lock"

previous_target=""
current_switched=false
release_dir=""
incoming_dir=""
deploy_lock_acquired=false

acquire_deploy_lock() {
  if ! mkdir -m 0700 "${deploy_lock_path}" 2>/dev/null; then
    printf 'Another Wall-Ant v2 deployment is running, or its stale lock needs review.\n' >&2
    return 1
  fi
  deploy_lock_acquired=true
  if ! printf '%s\n' "$$" >"${deploy_lock_path}/owner" \
    || ! chmod 0600 "${deploy_lock_path}/owner"; then
    release_deploy_lock || true
    return 1
  fi
}

release_deploy_lock() {
  [[ "${deploy_lock_acquired}" == true ]] || return 0
  [[ -d "${deploy_lock_path}" && ! -L "${deploy_lock_path}" ]] || return 1
  local owner=""
  IFS= read -r owner <"${deploy_lock_path}/owner" || return 1
  [[ "${owner}" == "$$" ]] || return 1
  rm -f -- "${deploy_lock_path}/owner" || return 1
  rmdir -- "${deploy_lock_path}" || return 1
  deploy_lock_acquired=false
}

on_exit() {
  local exit_code=$?
  trap - EXIT
  if ! release_deploy_lock; then
    printf 'Host deploy lock cleanup needs manual review.\n' >&2
    if [[ "${exit_code}" -eq 0 ]]; then
      exit_code=1
    fi
  fi
  exit "${exit_code}"
}

trap on_exit EXIT

is_managed_release_path() {
  local candidate="${1:-}"
  local kind="${2:-release}"
  [[ "${candidate}" == "${release_root}/"* ]] || return 1
  local name="${candidate#"${release_root}/"}"
  if [[ "${kind}" == incoming ]]; then
    [[ "${name}" =~ ^[0-9a-f]{40}\.[0-9]{8}T[0-9]{6}Z\.[0-9]+\.incoming$ ]]
  else
    [[ "${name}" =~ ^[0-9a-f]{40}\.[0-9]{8}T[0-9]{6}Z\.[0-9]+$ ]]
  fi
}

is_managed_previous_release_path() {
  local candidate="${1:-}"
  if is_managed_release_path "${candidate}" release; then
    return 0
  fi
  [[ "${candidate}" == "${release_root}/"* ]] || return 1
  local name="${candidate#"${release_root}/"}"
  [[ "${name}" =~ ^[0-9a-f]{40}$ ]]
}

validate_execution_safety_env() {
  local target_env_file="$1"
  python3 - "${target_env_file}" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
expected = {
    "WALLANT_EXECUTION_ENABLED": "false",
    "WALLANT_BROKER_ADAPTER": "disabled",
}
seen: dict[str, int] = {}
for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
    stripped = raw_line.strip()
    if not stripped or stripped.startswith("#"):
        continue
    assignment = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*=", raw_line)
    if assignment is None:
        continue
    key = assignment.group(1)
    if key not in expected:
        continue
    canonical_line = f"{key}={expected[key]}"
    if key in seen:
        raise SystemExit(
            f"duplicate safety key {key} at lines {seen[key]} and {line_number}"
        )
    seen[key] = line_number
    if raw_line != canonical_line:
        raise SystemExit(f"unsafe or non-canonical value for {key} at line {line_number}")

missing = [key for key in expected if key not in seen]
if missing:
    raise SystemExit(f"missing safety key(s): {', '.join(missing)}")
PY
}

attest_wallant_v2_inactive() {
  local context="$1"
  local inactive_state=""
  local inactive_state_status=0
  local inactive_pid=""
  local inactive_pid_status=0

  inactive_state="$(systemctl is-active wallant-v2-api.service 2>/dev/null)" \
    || inactive_state_status=$?
  inactive_pid="$(systemctl show wallant-v2-api.service -p MainPID --value 2>/dev/null)" \
    || inactive_pid_status=$?
  if [[ "${inactive_state_status}" -eq 3 \
    && "${inactive_state}" == inactive \
    && "${inactive_pid_status}" -eq 0 \
    && "${inactive_pid}" == 0 ]]; then
    return 0
  fi

  printf '%s stop not attested (state_status=%s state=%s pid_status=%s pid=%s).\n' \
    "${context}" "${inactive_state_status}" "${inactive_state:-unavailable}" \
    "${inactive_pid_status}" "${inactive_pid:-unavailable}" >&2
  return 1
}

cleanup_failed_attempt() {
  local cleanup_completed_release="${1:-true}"
  local current_target=""
  if [[ -L "${current_link}" ]]; then
    current_target="$(readlink -f "${current_link}" 2>/dev/null || true)"
  fi
  if [[ -n "${incoming_dir}" && ( -d "${incoming_dir}" || -L "${incoming_dir}" ) ]]; then
    is_managed_release_path "${incoming_dir}" incoming || {
      printf 'Refused unsafe incoming cleanup path: %s\n' "${incoming_dir}" >&2
      return 1
    }
    rm -rf -- "${incoming_dir}" || return 1
  fi
  if [[ "${cleanup_completed_release}" == true \
    && -n "${release_dir}" \
    && -d "${release_dir}" \
    && "${release_dir}" != "${current_target}" \
    && "${release_dir}" != "${previous_target}" ]]; then
    is_managed_release_path "${release_dir}" release || {
      printf 'Refused unsafe release cleanup path: %s\n' "${release_dir}" >&2
      return 1
    }
    rm -rf -- "${release_dir}" || return 1
  fi
}

prune_completed_releases() {
  local current_target=""
  local recent_count=0
  local release_name=""
  local candidate=""
  local kept_candidate=""
  local is_kept=false
  local -a kept_releases=()
  local -a completed_releases=()

  if [[ -L "${current_link}" ]]; then
    current_target="$(readlink -f "${current_link}")"
    is_managed_release_path "${current_target}" release || return 1
    kept_releases+=("${current_target}")
  fi
  if [[ -n "${previous_target}" && "${previous_target}" != "${current_target}" ]]; then
    is_managed_previous_release_path "${previous_target}" || return 1
    kept_releases+=("${previous_target}")
  fi
  while IFS= read -r release_name; do
    candidate="${release_root}/${release_name}"
    is_managed_release_path "${candidate}" release || continue
    completed_releases+=("${candidate}")
    if (( recent_count < retain_recent_releases )); then
      kept_releases+=("${candidate}")
      ((recent_count += 1))
    fi
  done < <(
    for candidate in "${release_root}"/*; do
      [[ -d "${candidate}" && ! -L "${candidate}" ]] || continue
      release_name="${candidate#"${release_root}/"}"
      is_managed_release_path "${candidate}" release || continue
      printf '%s|%s\n' "${release_name#*.}" "${release_name}"
    done |
      sort -t '|' -k1,1r |
      cut -d '|' -f2-
  )

  for candidate in "${completed_releases[@]}"; do
    is_kept=false
    for kept_candidate in "${kept_releases[@]}"; do
      if [[ "${candidate}" == "${kept_candidate}" ]]; then
        is_kept=true
        break
      fi
    done
    if [[ "${is_kept}" == false ]]; then
      is_managed_release_path "${candidate}" release || return 1
      rm -rf -- "${candidate}" || return 1
    fi
  done
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  rollback_on_error 1
}

rollback_on_error() {
  local exit_code="${1:-$?}"
  trap - ERR HUP INT TERM
  set +e
  local rollback_verified=false

  if [[ "${current_switched}" == true ]]; then
    if [[ -n "${previous_target}" && -d "${previous_target}" ]]; then
      local link_restored=false
      local unit_restored=true
      local service_restarted=false
      local rollback_health=""
      local previous_sha=""
      local previous_name=""
      local previous_release_mode=invalid
      local health_verified=false
      if ln -sfn "${previous_target}" "${deploy_root}/current.rollback" \
        && mv -Tf "${deploy_root}/current.rollback" "${current_link}"; then
        link_restored=true
      fi
      if [[ -f "${previous_target}/v2/infra/systemd/wallant-v2-api.service" ]]; then
        if ! install -o root -g root -m 0644 \
          "${previous_target}/v2/infra/systemd/wallant-v2-api.service" "${systemd_unit}" \
          || ! systemctl daemon-reload; then
          unit_restored=false
        fi
      else
        unit_restored=false
      fi
      if is_managed_previous_release_path "${previous_target}"; then
        previous_name="${previous_target#"${release_root}/"}"
        if [[ -f "${previous_target}/release.env" ]]; then
          previous_sha="$(sed -n 's/^WALLANT_BUILD_SHA=//p' "${previous_target}/release.env" 2>/dev/null)"
          if is_managed_release_path "${previous_target}" release \
            && [[ "${previous_sha}" =~ ^[0-9a-f]{40}$ \
            && "${previous_name%%.*}" == "${previous_sha}" ]]; then
            previous_release_mode=sha-attested
          fi
        elif [[ "${previous_name}" =~ ^[0-9a-f]{40}$ ]]; then
          previous_sha="${previous_name}"
          previous_release_mode=legacy-path-attested
        fi
      fi

      if [[ "${link_restored}" == true && "${unit_restored}" == true ]] \
        && systemctl restart wallant-v2-api.service \
        && systemctl is-active --quiet wallant-v2-api.service \
        && rollback_health="$(curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:8000/health)"; then
        service_restarted=true
      fi

      if [[ "${service_restarted}" == true \
        && "${previous_release_mode}" == sha-attested ]] \
        && printf '%s' "${rollback_health}" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
if payload.get("status") != "UP":
    raise SystemExit(1)
if payload.get("execution_enabled") is not False:
    raise SystemExit(1)
if payload.get("broker_adapter") != "disabled":
    raise SystemExit(1)
if payload.get("build_sha") != sys.argv[1]:
    raise SystemExit(1)
' "${previous_sha}"; then
        health_verified=true
        rollback_verified=true
        printf 'ROLLBACK_STATUS=verified previous=%s sha=%s\n' \
          "${previous_target}" "${previous_sha}" >&2
      elif [[ "${service_restarted}" == true \
        && "${previous_release_mode}" == legacy-path-attested ]] \
        && printf '%s' "${rollback_health}" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
if payload.get("status") != "UP":
    raise SystemExit(1)
if payload.get("execution_enabled") is not False:
    raise SystemExit(1)
if payload.get("broker_adapter") != "disabled":
    raise SystemExit(1)
'; then
        health_verified=true
        rollback_verified=true
        printf 'ROLLBACK_STATUS=verified-legacy previous=%s path_sha=%s health_sha=unavailable\n' \
          "${previous_target}" "${previous_sha}" >&2
      fi

      if [[ "${health_verified}" != true ]]; then
        if [[ "${service_restarted}" == true ]]; then
          printf 'ROLLBACK_STATUS=failed previous=%s mode=%s; service is active but rollback attestation requires manual review.\n' \
            "${previous_target}" "${previous_release_mode}" >&2
        else
          printf 'ROLLBACK_STATUS=failed previous=%s mode=%s; service and current require manual review.\n' \
            "${previous_target}" "${previous_release_mode}" >&2
        fi
      fi
    else
      local stop_status=0
      systemctl stop wallant-v2-api.service || stop_status=$?
      systemctl disable wallant-v2-api.service || true
      if [[ "${stop_status}" -eq 0 ]] \
        && attest_wallant_v2_inactive "First deployment rollback"; then
        if [[ ! -L "${current_link}" ]] || rm -f -- "${current_link}"; then
          rollback_verified=true
          printf 'ROLLBACK_STATUS=verified first deployment stopped; MySQL data retained.\n' >&2
        else
          printf 'ROLLBACK_STATUS=failed first deployment stopped but current link removal failed; release preserved for manual review.\n' >&2
        fi
      else
        printf 'ROLLBACK_STATUS=failed first deployment stop not attested (stop=%s); current and release preserved for manual review.\n' \
          "${stop_status}" >&2
      fi
    fi
  fi
  if [[ "${current_switched}" == true && "${rollback_verified}" != true ]]; then
    cleanup_failed_attempt false || printf 'Failed incoming cleanup needs manual review.\n' >&2
    if [[ -n "${release_dir}" && -d "${release_dir}" ]]; then
      printf 'FAILED_RELEASE_PRESERVED=%s reason=rollback-unverified\n' \
        "${release_dir}" >&2
    fi
  else
    cleanup_failed_attempt true || printf 'Failed attempt cleanup needs manual review.\n' >&2
  fi

  exit "${exit_code}"
}

rollback_on_signal() {
  local signal_name="$1"
  local exit_code="$2"
  printf 'Received %s during deployment; rolling back before releasing the host lock.\n' \
    "${signal_name}" >&2
  rollback_on_error "${exit_code}"
}

trap 'rollback_on_error $?' ERR
trap 'rollback_on_signal HUP 129' HUP
trap 'rollback_on_signal INT 130' INT
trap 'rollback_on_signal TERM 143' TERM

[[ "${EUID}" -eq 0 ]] || fail "Run this script as root."
[[ -d /run/lock && ! -L /run/lock ]] || fail "/run/lock must be a real directory."
acquire_deploy_lock || fail "Could not acquire the host deployment lock."
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

for command_name in python3 docker systemctl curl openssl ss tar sha256sum runuser sort cut readlink; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "Missing required command: ${command_name}"
done
python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' \
  || fail "Python 3.12 or newer is required."
python3 -c 'import ensurepip, venv' \
  || fail "Python venv support is required; install python3.12-venv."
docker compose version >/dev/null 2>&1 || fail "Docker Compose plugin is required."
docker info >/dev/null 2>&1 || fail "Docker daemon is not available."

if [[ -L "${current_link}" ]]; then
  candidate_previous_target="$(readlink -f "${current_link}")"
  is_managed_previous_release_path "${candidate_previous_target}" \
    || fail "Current release link points outside a managed immutable release: ${candidate_previous_target}"
  if systemctl is-active --quiet wallant-v2-api.service \
    && curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:8000/health >/dev/null; then
    previous_target="${candidate_previous_target}"
  else
    previous_stop_status=0
    previous_disable_status=0
    systemctl stop wallant-v2-api.service || previous_stop_status=$?
    systemctl disable wallant-v2-api.service || previous_disable_status=$?
    if [[ "${previous_stop_status}" -ne 0 \
      || "${previous_disable_status}" -ne 0 ]] \
      || ! attest_wallant_v2_inactive "Existing unhealthy v2"; then
      fail "Existing unhealthy v2 release could not be safely quiesced before deployment."
    fi
    printf 'Existing unhealthy v2 release was stopped and excluded from automatic rollback: %s\n' \
      "${candidate_previous_target}" >&2
  fi
fi

getent group wallant >/dev/null 2>&1 || groupadd --system wallant
id -u wallant >/dev/null 2>&1 \
  || useradd --system --gid wallant --home-dir "${data_root}" --shell /usr/sbin/nologin wallant

install -d -o root -g root -m 0755 "${deploy_root}" "${release_root}"
install -d -o wallant -g wallant -m 0750 "${data_root}" "${data_root}/market" "${log_root}"
install -d -o root -g wallant -m 0750 "${config_root}"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
release_dir="${release_root}/${release_sha}.${timestamp}.${BASHPID}"
incoming_dir="${release_dir}.incoming"
[[ ! -e "${release_dir}" && ! -L "${release_dir}" ]] \
  || fail "Immutable release path already exists: ${release_dir}"
[[ ! -e "${incoming_dir}" && ! -L "${incoming_dir}" ]] \
  || fail "Immutable incoming path already exists: ${incoming_dir}"
install -d -o root -g root -m 0755 "${incoming_dir}"
tar -xzf "${archive_path}" -C "${incoming_dir}"
install -o root -g wallant -m 0640 /dev/null "${incoming_dir}/release.env"
printf 'WALLANT_BUILD_SHA=%s\n' "${release_sha}" >"${incoming_dir}/release.env"

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

validate_execution_safety_env "${env_file}" \
  || fail "Execution safety environment is missing, duplicated, or unsafe in ${env_file}."

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

mv "${incoming_dir}" "${release_dir}"
ln -sfn "${release_dir}" "${deploy_root}/current.next"
current_switched=true
mv -Tf "${deploy_root}/current.next" "${current_link}"

install -o root -g root -m 0644 \
  "${release_dir}/v2/infra/systemd/wallant-v2-api.service" "${systemd_unit}"
systemctl daemon-reload
systemctl enable --now wallant-v2-api.service
systemctl restart wallant-v2-api.service

health_payload=""
for attempt in $(seq 1 30); do
  if health_payload="$(curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:8000/health)"; then
    break
  fi
  if [[ "${attempt}" -eq 30 ]]; then
    systemctl status wallant-v2-api.service --no-pager || true
    journalctl -u wallant-v2-api.service -n 80 --no-pager || true
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
if payload.get("build_sha") != sys.argv[1]:
    raise SystemExit("deployed build SHA does not match the requested release")
' "${release_sha}"
systemctl is-active --quiet wallant-v2-api.service

retention_status=ok
if ! prune_completed_releases; then
  retention_status=warning
  printf 'Release retention cleanup needs manual review.\n' >&2
fi

printf 'DEPLOYED_RELEASE=%s\n' "$(readlink -f "${current_link}")"
printf 'API_HEALTH=%s\n' "${health_payload}"
printf 'EXECUTION_SAFETY=disabled\n'
printf 'RELEASE_RETENTION=%s\n' "${retention_status}"
