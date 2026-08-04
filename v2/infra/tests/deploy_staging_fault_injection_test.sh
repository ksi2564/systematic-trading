#!/usr/bin/env bash
set -Eeuo pipefail

readonly fault_test_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly fault_deploy_script="${fault_test_script_dir}/../scripts/deploy_staging.sh"
readonly fault_test_root_input="$(mktemp -d /tmp/wallant-deploy-fault.XXXXXX)"
readonly fault_test_root="$(cd -- "${fault_test_root_input}" && pwd -P)"
readonly fault_fake_bin="${fault_test_root}/fake-bin"
readonly fault_real_python="$(command -v python3)"
readonly fault_original_path="${PATH}"
readonly fault_release_sha="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
readonly fault_previous_sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
readonly fault_mismatched_sha="cccccccccccccccccccccccccccccccccccccccc"
readonly fault_archive_path="/tmp/wallant-v2-fault-${$}.tgz"

fault_cleanup() {
  if [[ "${WALLANT_FAULT_KEEP_ARTIFACTS:-false}" == true ]]; then
    printf 'fault-injection artifacts retained at %s\n' "${fault_test_root}" >&2
    return 0
  fi
  [[ "${fault_test_root}" == /tmp/wallant-deploy-fault.* \
    || "${fault_test_root}" == /private/tmp/wallant-deploy-fault.* ]] || return 1
  [[ "${fault_archive_path}" == /tmp/wallant-v2-fault-*.tgz ]] || return 1
  rm -rf -- "${fault_test_root}"
  rm -f -- "${fault_archive_path}"
}
trap fault_cleanup EXIT

fault_write_fake_commands() {
  mkdir -p "${fault_fake_bin}"

  cat >"${fault_fake_bin}/install" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
directory_mode=false
permission_mode=""
declare -a operands=()
while (($# > 0)); do
  case "$1" in
    -d)
      directory_mode=true
      shift
      ;;
    -m)
      permission_mode="$2"
      shift 2
      ;;
    -o|-g)
      shift 2
      ;;
    --)
      shift
      ;;
    -*)
      printf 'unsupported fake install option: %s\n' "$1" >&2
      exit 2
      ;;
    *)
      operands+=("$1")
      shift
      ;;
  esac
done

if [[ "${directory_mode}" == true ]]; then
  for target in "${operands[@]}"; do
    mkdir -p -- "${target}"
    [[ -z "${permission_mode}" ]] || chmod "${permission_mode}" "${target}"
  done
  exit 0
fi

[[ "${#operands[@]}" -eq 2 ]]
source_path="${operands[0]}"
target_path="${operands[1]}"
mkdir -p -- "$(dirname -- "${target_path}")"
if [[ "${source_path}" == /dev/null ]]; then
  : >"${target_path}"
else
  cp -- "${source_path}" "${target_path}"
fi
[[ -z "${permission_mode}" ]] || chmod "${permission_mode}" "${target_path}"
SH

  cat >"${fault_fake_bin}/python3" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == -m && "${2:-}" == venv ]]; then
  venv_path="$3"
  mkdir -p "${venv_path}/bin"
  cat >"${venv_path}/bin/python" <<'PY'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == -m && "${2:-}" == pip ]]; then
  exit 0
fi
exec "${FAULT_REAL_PYTHON:?}" "$@"
PY
  cat >"${venv_path}/bin/alembic" <<'AL'
#!/usr/bin/env bash
exit 0
AL
  chmod 0755 "${venv_path}/bin/python" "${venv_path}/bin/alembic"
  exit 0
fi
exec "${FAULT_REAL_PYTHON:?}" "$@"
SH

  cat >"${fault_fake_bin}/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  info)
    exit 0
    ;;
  inspect)
    printf 'healthy\n'
    exit 0
    ;;
  compose)
    for argument in "$@"; do
      case "${argument}" in
        version|up)
          exit 0
          ;;
        ps)
          printf 'fault-mysql-container\n'
          exit 0
          ;;
      esac
    done
    ;;
esac
printf 'unsupported fake docker command: %s\n' "$*" >&2
exit 2
SH

  cat >"${fault_fake_bin}/systemctl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
runtime_sha_from_current() {
  [[ -L "${FAULT_CURRENT_LINK:?}" ]] || return 1
  local current_target=""
  local current_sha=""
  current_target="$("${FAULT_REAL_PYTHON:?}" -c \
    'import os, sys; print(os.path.realpath(sys.argv[1]))' \
    "${FAULT_CURRENT_LINK}")"
  current_sha="$(sed -n 's/^WALLANT_BUILD_SHA=//p' "${current_target}/release.env" 2>/dev/null || true)"
  if [[ -z "${current_sha}" ]]; then
    current_sha="${current_target##*/}"
  fi
  [[ "${current_sha}" =~ ^[0-9a-f]{40}$ ]]
  printf '%s\n' "${current_sha}" >"${FAULT_RUNTIME_SHA_FILE:?}"
}

command_name="${1:-}"
shift || true
case "${command_name}" in
  daemon-reload|disable|status)
    exit 0
    ;;
  enable)
    printf 'active\n' >"${FAULT_SERVICE_STATE:?}"
    runtime_sha_from_current
    exit 0
    ;;
  stop)
    if [[ "${FAULT_SCENARIO:?}" == first-deploy-stop-fail \
      || "${FAULT_SCENARIO}" == previous-unhealthy-stop-fail ]]; then
      exit 1
    fi
    printf 'inactive\n' >"${FAULT_SERVICE_STATE:?}"
    : >"${FAULT_RUNTIME_SHA_FILE:?}"
    exit 0
    ;;
  restart)
    if [[ "${FAULT_SCENARIO:?}" == systemctl-restart-fail \
      && ! -e "${FAULT_MARKER_DIR:?}/restart-failed" ]]; then
      : >"${FAULT_MARKER_DIR}/restart-failed"
      exit 1
    fi
    if [[ "${FAULT_SCENARIO}" == rollback-restart-fail \
      && -e "${FAULT_MARKER_DIR:?}/new-release-seen" ]]; then
      exit 1
    fi
    printf 'active\n' >"${FAULT_SERVICE_STATE:?}"
    runtime_sha_from_current
    if [[ "${FAULT_SCENARIO}" == signal-term \
      && ! -e "${FAULT_MARKER_DIR}/signal-sent" ]]; then
      : >"${FAULT_MARKER_DIR}/signal-sent"
      kill -TERM "${PPID}"
    fi
    if [[ "${FAULT_SCENARIO}" == signal-hup \
      && ! -e "${FAULT_MARKER_DIR}/signal-sent" ]]; then
      : >"${FAULT_MARKER_DIR}/signal-sent"
      kill -HUP "${PPID}"
    fi
    exit 0
    ;;
  is-active)
    current_state="$(cat "${FAULT_SERVICE_STATE:?}")"
    if [[ "${FAULT_SCENARIO:?}" == first-deploy-state-query-fail \
      && "${current_state}" == inactive \
      && -e "${FAULT_MARKER_DIR:?}/new-release-seen" ]]; then
      exit 4
    fi
    quiet=false
    for argument in "$@"; do
      [[ "${argument}" == --quiet ]] && quiet=true
    done
    if [[ "${current_state}" == active ]]; then
      [[ "${quiet}" == true ]] || printf 'active\n'
      exit 0
    fi
    [[ "${quiet}" == true ]] || printf 'inactive\n'
    exit 3
    ;;
  show)
    if [[ "$(cat "${FAULT_SERVICE_STATE:?}")" == active ]]; then
      printf '4242\n'
    else
      printf '0\n'
    fi
    exit 0
    ;;
  *)
    printf 'unsupported fake systemctl command: %s %s\n' \
      "${command_name}" "$*" >&2
    exit 2
    ;;
esac
SH

  cat >"${fault_fake_bin}/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
current_sha="$(cat "${FAULT_RUNTIME_SHA_FILE:?}")"
[[ "${current_sha}" =~ ^[0-9a-f]{40}$ ]] || exit 22

if [[ "${current_sha}" == "${FAULT_PREVIOUS_SHA:?}" \
  && "${FAULT_SCENARIO:?}" == previous-unhealthy-stop-fail \
  && ! -e "${FAULT_MARKER_DIR:?}/new-release-seen" ]]; then
  exit 22
fi

if [[ "${current_sha}" == "${FAULT_RELEASE_SHA:?}" ]]; then
  : >"${FAULT_MARKER_DIR:?}/new-release-seen"
  case "${FAULT_SCENARIO:?}" in
    health-new-unreachable|legacy-previous-health-fail|rollback-health-fail|rollback-restart-fail|rollback-path-sha-mismatch|first-deploy-health-fail|first-deploy-stop-fail|first-deploy-state-query-fail)
      exit 22
      ;;
    health-new-unsafe)
      printf '{"status":"UP","execution_enabled":true,"broker_adapter":"disabled","build_sha":"%s"}\n' \
        "${current_sha}"
      exit 0
      ;;
    health-new-wrong-sha)
      printf '{"status":"UP","execution_enabled":false,"broker_adapter":"disabled","build_sha":"cccccccccccccccccccccccccccccccccccccccc"}\n'
      exit 0
      ;;
  esac
fi

if [[ "${current_sha}" == "${FAULT_PREVIOUS_SHA:?}" \
  && "${FAULT_SCENARIO:?}" == rollback-health-fail \
  && -e "${FAULT_MARKER_DIR:?}/new-release-seen" ]]; then
  exit 22
fi

printf '{"status":"UP","execution_enabled":false,"broker_adapter":"disabled","build_sha":"%s"}\n' \
  "${current_sha}"
SH

  cat >"${fault_fake_bin}/openssl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == rand && "${2:-}" == -hex ]]; then
  printf 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd\n'
elif [[ "${1:-}" == rand && "${2:-}" == -base64 ]]; then
  printf 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee=\n'
else
  exit 2
fi
SH

  cat >"${fault_fake_bin}/sha256sum" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
/usr/bin/shasum -a 256 "$@"
SH

  cat >"${fault_fake_bin}/readlink" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == -f ]]; then
  exec "${FAULT_REAL_PYTHON:?}" -c \
    'import os, sys; print(os.path.realpath(sys.argv[1]))' "$2"
fi
exec /usr/bin/readlink "$@"
SH

  cat >"${fault_fake_bin}/mv" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == -Tf ]]; then
  exec "${FAULT_REAL_PYTHON:?}" -c \
    'import os, sys; os.replace(sys.argv[1], sys.argv[2])' "$2" "$3"
fi
exec /bin/mv "$@"
SH

  cat >"${fault_fake_bin}/rmdir" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
target="${1:-}"
if [[ "${target}" == -- ]]; then
  target="${2:-}"
fi
if [[ "${FAULT_SCENARIO:?}" == lock-cleanup-fail \
  && "${target}" == */wallant-v2-deploy.lock ]]; then
  exit 1
fi
exec /bin/rmdir "$@"
SH

  for no_op_command in chown getent groupadd useradd runuser ss journalctl sleep; do
    cat >"${fault_fake_bin}/${no_op_command}" <<'SH'
#!/usr/bin/env bash
exit 0
SH
  done

  cat >"${fault_fake_bin}/id" <<'SH'
#!/usr/bin/env bash
printf '1000\n'
SH

  chmod 0755 "${fault_fake_bin}"/*
}

fault_build_archive() {
  local package_root="${fault_test_root}/package"
  mkdir -p \
    "${package_root}/v2/backend" \
    "${package_root}/v2/frontend/dist" \
    "${package_root}/v2/infra/systemd"
  printf '[project]\nname = "fault-package"\nversion = "0"\n' \
    >"${package_root}/v2/backend/pyproject.toml"
  printf '<div id="root"></div>\n' \
    >"${package_root}/v2/frontend/dist/index.html"
  printf 'services: {}\n' >"${package_root}/v2/infra/compose.yaml"
  printf 'new-unit\n' \
    >"${package_root}/v2/infra/systemd/wallant-v2-api.service"
  tar -C "${package_root}" -czf "${fault_archive_path}" v2
}

fault_transform_deploy_script() {
  local scenario_root="$1"
  local transformed_script="$2"
  awk \
    -v deploy_root="${scenario_root}/opt/wallant" \
    -v config_root="${scenario_root}/etc/wallant" \
    -v data_root="${scenario_root}/var/lib/wallant" \
    -v log_root="${scenario_root}/var/log/wallant" \
    -v systemd_unit="${scenario_root}/etc/systemd/system/wallant-v2-api.service" \
    -v deploy_lock_path="${scenario_root}/run/lock/wallant-v2-deploy.lock" \
    -v run_lock="${scenario_root}/run/lock" '
    $0 == "readonly deploy_root=\"/opt/wallant\"" {
      print "readonly deploy_root=\"" deploy_root "\""; replaced[1]++; next
    }
    $0 == "readonly config_root=\"/etc/wallant\"" {
      print "readonly config_root=\"" config_root "\""; replaced[2]++; next
    }
    $0 == "readonly data_root=\"/var/lib/wallant\"" {
      print "readonly data_root=\"" data_root "\""; replaced[3]++; next
    }
    $0 == "readonly log_root=\"/var/log/wallant\"" {
      print "readonly log_root=\"" log_root "\""; replaced[4]++; next
    }
    $0 == "readonly systemd_unit=\"/etc/systemd/system/wallant-v2-api.service\"" {
      print "readonly systemd_unit=\"" systemd_unit "\""; replaced[5]++; next
    }
    $0 == "readonly deploy_lock_path=\"/run/lock/wallant-v2-deploy.lock\"" {
      print "readonly deploy_lock_path=\"" deploy_lock_path "\""; replaced[6]++; next
    }
    $0 == "[[ \"${EUID}\" -eq 0 ]] || fail \"Run this script as root.\"" {
      print ": \"fault-injection copy intentionally bypasses only the root check\""
      replaced[7]++
      next
    }
    $0 == "[[ -d /run/lock && ! -L /run/lock ]] || fail \"/run/lock must be a real directory.\"" {
      print "[[ -d \"" run_lock "\" && ! -L \"" run_lock "\" ]] || fail \"fault test lock root must be a real directory.\""
      replaced[8]++
      next
    }
    $0 == "release_dir=\"${release_root}/${release_sha}.${timestamp}.${BASHPID}\"" {
      print "release_dir=\"${release_root}/${release_sha}.${timestamp}.${BASHPID:-$$}\""
      replaced[9]++
      next
    }
    { print }
    END {
      for (replacement_index = 1; replacement_index <= 9; replacement_index++) {
        if (replaced[replacement_index] != 1) {
          print "fault test transform contract changed at replacement " replacement_index > "/dev/stderr"
          exit 1
        }
      }
    }
  ' "${fault_deploy_script}" >"${transformed_script}"
  chmod 0755 "${transformed_script}"
}

fault_resolve_path() {
  "${fault_real_python}" -c \
    'import os, sys; print(os.path.realpath(sys.argv[1]))' "$1"
}

fault_prepare_scenario() {
  local scenario_name="$1"
  local previous_mode="$2"
  local scenario_root="${fault_test_root}/${scenario_name}"
  local config_root="${scenario_root}/etc/wallant"
  local previous_release=""
  local previous_runtime_sha="${fault_previous_sha}"

  mkdir -p \
    "${scenario_root}/run/lock" \
    "${scenario_root}/markers" \
    "${scenario_root}/opt/wallant/releases" \
    "${scenario_root}/etc/systemd/system" \
    "${config_root}"
  printf 'WALLANT_MYSQL_PASSWORD=fault\nWALLANT_MYSQL_ROOT_PASSWORD=fault-root\n' \
    >"${config_root}/mysql.env"
  {
    printf 'WALLANT_ENVIRONMENT=staging\n'
    printf 'WALLANT_DATABASE_URL=mysql+pymysql://wallant:fault@127.0.0.1:3307/wallant_v2\n'
    printf 'WALLANT_EXECUTION_ENABLED=false\n'
    printf 'WALLANT_BROKER_ADAPTER=disabled\n'
  } >"${config_root}/v2.env"
  if [[ "${scenario_name}" == duplicate-conflicting-safety ]]; then
    {
      printf 'WALLANT_EXECUTION_ENABLED=true\n'
      printf 'WALLANT_BROKER_ADAPTER=kis-live\n'
    } >>"${config_root}/v2.env"
  elif [[ "${scenario_name}" == duplicate-whitespace-conflicting-safety ]]; then
    {
      printf ' WALLANT_EXECUTION_ENABLED=true\n'
      printf 'WALLANT_BROKER_ADAPTER = kis-live\n'
    } >>"${config_root}/v2.env"
  fi

  case "${previous_mode}" in
    modern)
      previous_release="${scenario_root}/opt/wallant/releases/${fault_previous_sha}.20260805T000000Z.1"
      mkdir -p "${previous_release}/v2/infra/systemd"
      printf 'WALLANT_BUILD_SHA=%s\n' "${fault_previous_sha}" \
        >"${previous_release}/release.env"
      ;;
    modern-mismatched)
      previous_runtime_sha="${fault_mismatched_sha}"
      previous_release="${scenario_root}/opt/wallant/releases/${fault_previous_sha}.20260805T000000Z.1"
      mkdir -p "${previous_release}/v2/infra/systemd"
      printf 'WALLANT_BUILD_SHA=%s\n' "${fault_mismatched_sha}" \
        >"${previous_release}/release.env"
      ;;
    legacy)
      previous_release="${scenario_root}/opt/wallant/releases/${fault_previous_sha}"
      mkdir -p "${previous_release}/v2/infra/systemd"
      ;;
    same-sha)
      previous_runtime_sha="${fault_release_sha}"
      previous_release="${scenario_root}/opt/wallant/releases/${fault_release_sha}.20260805T000000Z.1"
      mkdir -p "${previous_release}/v2/infra/systemd"
      printf 'WALLANT_BUILD_SHA=%s\n' "${fault_release_sha}" \
        >"${previous_release}/release.env"
      ;;
    none)
      ;;
    *)
      printf 'unknown previous mode: %s\n' "${previous_mode}" >&2
      return 1
      ;;
  esac

  if [[ "${previous_mode}" != none ]]; then
    printf 'old-unit\n' \
      >"${previous_release}/v2/infra/systemd/wallant-v2-api.service"
    printf 'old-unit\n' \
      >"${scenario_root}/etc/systemd/system/wallant-v2-api.service"
    ln -s "${previous_release}" "${scenario_root}/opt/wallant/current"
    printf 'active\n' >"${scenario_root}/service.state"
    printf '%s\n' "${previous_runtime_sha}" >"${scenario_root}/runtime.sha"
  else
    printf 'inactive\n' >"${scenario_root}/service.state"
    : >"${scenario_root}/runtime.sha"
  fi

  fault_transform_deploy_script \
    "${scenario_root}" "${scenario_root}/deploy_staging.test.sh"
}

fault_assert_no_new_release() {
  local scenario_root="$1"
  if find "${scenario_root}/opt/wallant/releases" -mindepth 1 -maxdepth 1 \
    -type d -name "${fault_release_sha}.*" | grep -q .; then
    printf 'failed release directory was retained for %s\n' "${scenario_root}" >&2
    return 1
  fi
}

fault_assert_new_release_preserved() {
  local scenario_root="$1"
  find "${scenario_root}/opt/wallant/releases" -mindepth 1 -maxdepth 1 \
    -type d -name "${fault_release_sha}.*" | grep -q .
}

fault_run_scenario() {
  local scenario_name="$1"
  local expected_exit="$2"
  local previous_mode="$3"
  local expected_result="$4"
  local scenario_root="${fault_test_root}/${scenario_name}"
  local output_file="${scenario_root}/output.log"
  local archive_sha=""
  local actual_exit=0
  local current_target=""

  fault_prepare_scenario "${scenario_name}" "${previous_mode}"
  archive_sha="$(/usr/bin/shasum -a 256 "${fault_archive_path}" | awk '{print $1}')"

  set +e
  PATH="${fault_fake_bin}:${fault_original_path}" \
  FAULT_REAL_PYTHON="${fault_real_python}" \
  FAULT_SCENARIO="${scenario_name}" \
  FAULT_CURRENT_LINK="${scenario_root}/opt/wallant/current" \
  FAULT_SERVICE_STATE="${scenario_root}/service.state" \
  FAULT_RUNTIME_SHA_FILE="${scenario_root}/runtime.sha" \
  FAULT_MARKER_DIR="${scenario_root}/markers" \
  FAULT_RELEASE_SHA="${fault_release_sha}" \
  FAULT_PREVIOUS_SHA="${fault_previous_sha}" \
    bash "${scenario_root}/deploy_staging.test.sh" \
      "${fault_archive_path}" "${fault_release_sha}" "${archive_sha}" \
      >"${output_file}" 2>&1
  actual_exit=$?
  set -e

  if [[ "${actual_exit}" -ne "${expected_exit}" ]]; then
    printf 'scenario %s exited %s, expected %s\n' \
      "${scenario_name}" "${actual_exit}" "${expected_exit}" >&2
    sed -n '1,240p' "${output_file}" >&2
    return 1
  fi
  if [[ "${expected_result}" == deployed-lock-preserved ]]; then
    [[ -d "${scenario_root}/run/lock/wallant-v2-deploy.lock" ]]
    [[ ! -e "${scenario_root}/run/lock/wallant-v2-deploy.lock/owner" ]]
  else
    [[ ! -e "${scenario_root}/run/lock/wallant-v2-deploy.lock" ]]
  fi

  case "${expected_result}" in
    deployed|deployed-lock-preserved|deployed-same-sha)
      [[ -L "${scenario_root}/opt/wallant/current" ]]
      current_target="$(fault_resolve_path "${scenario_root}/opt/wallant/current")"
      [[ "${current_target}" == "${scenario_root}/opt/wallant/releases/${fault_release_sha}."* ]]
      grep -Fx "WALLANT_BUILD_SHA=${fault_release_sha}" \
        "${current_target}/release.env" >/dev/null
      grep -Fx 'new-unit' \
        "${scenario_root}/etc/systemd/system/wallant-v2-api.service" >/dev/null
      grep -Fx 'active' "${scenario_root}/service.state" >/dev/null
      grep -F 'EXECUTION_SAFETY=disabled' "${output_file}" >/dev/null
      if [[ "${expected_result}" == deployed-same-sha ]]; then
        [[ "${current_target}" != \
          "${scenario_root}/opt/wallant/releases/${fault_release_sha}.20260805T000000Z.1" ]]
        [[ -d "${scenario_root}/opt/wallant/releases/${fault_release_sha}.20260805T000000Z.1" ]]
      fi
      if [[ "${expected_result}" == deployed-lock-preserved ]]; then
        grep -F 'Host deploy lock cleanup needs manual review.' \
          "${output_file}" >/dev/null
      fi
      ;;
    rolled-back)
      [[ -L "${scenario_root}/opt/wallant/current" ]]
      current_target="$(fault_resolve_path "${scenario_root}/opt/wallant/current")"
      [[ "${current_target}" == \
        "${scenario_root}/opt/wallant/releases/${fault_previous_sha}.20260805T000000Z.1" ]]
      grep -Fx 'old-unit' \
        "${scenario_root}/etc/systemd/system/wallant-v2-api.service" >/dev/null
      grep -Fx 'active' "${scenario_root}/service.state" >/dev/null
      grep -F 'ROLLBACK_STATUS=verified' "${output_file}" >/dev/null
      fault_assert_no_new_release "${scenario_root}"
      ;;
    rolled-back-legacy)
      [[ -L "${scenario_root}/opt/wallant/current" ]]
      current_target="$(fault_resolve_path "${scenario_root}/opt/wallant/current")"
      [[ "${current_target}" == \
        "${scenario_root}/opt/wallant/releases/${fault_previous_sha}" ]]
      grep -Fx 'old-unit' \
        "${scenario_root}/etc/systemd/system/wallant-v2-api.service" >/dev/null
      grep -Fx 'active' "${scenario_root}/service.state" >/dev/null
      grep -Fx "${fault_previous_sha}" "${scenario_root}/runtime.sha" >/dev/null
      grep -F 'ROLLBACK_STATUS=verified-legacy' "${output_file}" >/dev/null
      fault_assert_no_new_release "${scenario_root}"
      ;;
    rollback-failed-closed)
      [[ -L "${scenario_root}/opt/wallant/current" ]]
      current_target="$(fault_resolve_path "${scenario_root}/opt/wallant/current")"
      [[ "${current_target}" == \
        "${scenario_root}/opt/wallant/releases/${fault_previous_sha}.20260805T000000Z.1" ]]
      grep -F 'ROLLBACK_STATUS=failed' "${output_file}" >/dev/null
      grep -F 'FAILED_RELEASE_PRESERVED=' "${output_file}" >/dev/null
      fault_assert_new_release_preserved "${scenario_root}"
      ;;
    first-deploy-stopped)
      [[ ! -e "${scenario_root}/opt/wallant/current" ]]
      grep -Fx 'inactive' "${scenario_root}/service.state" >/dev/null
      grep -F 'ROLLBACK_STATUS=verified first deployment stopped' \
        "${output_file}" >/dev/null
      fault_assert_no_new_release "${scenario_root}"
      ;;
    first-deploy-preserved)
      [[ -L "${scenario_root}/opt/wallant/current" ]]
      current_target="$(fault_resolve_path "${scenario_root}/opt/wallant/current")"
      [[ "${current_target}" == "${scenario_root}/opt/wallant/releases/${fault_release_sha}."* ]]
      grep -Fx 'active' "${scenario_root}/service.state" >/dev/null
      grep -Fx "${fault_release_sha}" "${scenario_root}/runtime.sha" >/dev/null
      grep -F 'ROLLBACK_STATUS=failed first deployment stop not attested' \
        "${output_file}" >/dev/null
      grep -F 'FAILED_RELEASE_PRESERVED=' "${output_file}" >/dev/null
      fault_assert_new_release_preserved "${scenario_root}"
      ;;
    first-deploy-query-unverified)
      [[ -L "${scenario_root}/opt/wallant/current" ]]
      current_target="$(fault_resolve_path "${scenario_root}/opt/wallant/current")"
      [[ "${current_target}" == "${scenario_root}/opt/wallant/releases/${fault_release_sha}."* ]]
      grep -Fx 'inactive' "${scenario_root}/service.state" >/dev/null
      [[ ! -s "${scenario_root}/runtime.sha" ]]
      grep -F 'ROLLBACK_STATUS=failed first deployment stop not attested' \
        "${output_file}" >/dev/null
      grep -F 'state_status=4' "${output_file}" >/dev/null
      grep -F 'FAILED_RELEASE_PRESERVED=' "${output_file}" >/dev/null
      fault_assert_new_release_preserved "${scenario_root}"
      ;;
    pre-switch-rejected)
      [[ -L "${scenario_root}/opt/wallant/current" ]]
      current_target="$(fault_resolve_path "${scenario_root}/opt/wallant/current")"
      [[ "${current_target}" == \
        "${scenario_root}/opt/wallant/releases/${fault_previous_sha}.20260805T000000Z.1" ]]
      grep -Fx 'old-unit' \
        "${scenario_root}/etc/systemd/system/wallant-v2-api.service" >/dev/null
      grep -Fx 'active' "${scenario_root}/service.state" >/dev/null
      grep -Fx "${fault_previous_sha}" "${scenario_root}/runtime.sha" >/dev/null
      grep -F 'duplicate safety key WALLANT_EXECUTION_ENABLED' \
        "${output_file}" >/dev/null
      grep -F 'Execution safety environment is missing, duplicated, or unsafe' \
        "${output_file}" >/dev/null
      [[ ! -e "${scenario_root}/markers/new-release-seen" ]]
      fault_assert_no_new_release "${scenario_root}"
      ;;
    previous-unhealthy-preserved)
      [[ -L "${scenario_root}/opt/wallant/current" ]]
      current_target="$(fault_resolve_path "${scenario_root}/opt/wallant/current")"
      [[ "${current_target}" == \
        "${scenario_root}/opt/wallant/releases/${fault_previous_sha}.20260805T000000Z.1" ]]
      grep -Fx 'old-unit' \
        "${scenario_root}/etc/systemd/system/wallant-v2-api.service" >/dev/null
      grep -Fx 'active' "${scenario_root}/service.state" >/dev/null
      grep -Fx "${fault_previous_sha}" "${scenario_root}/runtime.sha" >/dev/null
      grep -F 'Existing unhealthy v2 release could not be safely quiesced before deployment' \
        "${output_file}" >/dev/null
      [[ ! -e "${scenario_root}/markers/new-release-seen" ]]
      fault_assert_no_new_release "${scenario_root}"
      ;;
    *)
      printf 'unknown expected result: %s\n' "${expected_result}" >&2
      return 1
      ;;
  esac

  case "${scenario_name}" in
    signal-term)
      grep -F 'Received TERM during deployment' "${output_file}" >/dev/null
      ;;
    signal-hup)
      grep -F 'Received HUP during deployment' "${output_file}" >/dev/null
      ;;
  esac
}

fault_run_preexisting_lock_case() {
  local scenario_name="$1"
  local owner_value="$2"
  local scenario_root="${fault_test_root}/${scenario_name}"
  local lock_path="${scenario_root}/run/lock/wallant-v2-deploy.lock"
  local output_file="${scenario_root}/output.log"
  local archive_sha=""
  local actual_exit=0

  fault_prepare_scenario "${scenario_name}" modern
  mkdir -m 0700 "${lock_path}"
  printf '%s\n' "${owner_value}" >"${lock_path}/owner"
  chmod 0600 "${lock_path}/owner"
  archive_sha="$(/usr/bin/shasum -a 256 "${fault_archive_path}" | awk '{print $1}')"

  set +e
  PATH="${fault_fake_bin}:${fault_original_path}" \
  FAULT_REAL_PYTHON="${fault_real_python}" \
  FAULT_SCENARIO="${scenario_name}" \
  FAULT_CURRENT_LINK="${scenario_root}/opt/wallant/current" \
  FAULT_SERVICE_STATE="${scenario_root}/service.state" \
  FAULT_RUNTIME_SHA_FILE="${scenario_root}/runtime.sha" \
  FAULT_MARKER_DIR="${scenario_root}/markers" \
  FAULT_RELEASE_SHA="${fault_release_sha}" \
  FAULT_PREVIOUS_SHA="${fault_previous_sha}" \
    bash "${scenario_root}/deploy_staging.test.sh" \
      "${fault_archive_path}" "${fault_release_sha}" "${archive_sha}" \
      >"${output_file}" 2>&1
  actual_exit=$?
  set -e

  [[ "${actual_exit}" -eq 1 ]]
  [[ -d "${lock_path}" ]]
  grep -Fx "${owner_value}" "${lock_path}/owner" >/dev/null
  grep -F 'Another Wall-Ant v2 deployment is running' "${output_file}" >/dev/null
  grep -F 'ERROR: Could not acquire the host deployment lock.' \
    "${output_file}" >/dev/null
  [[ "$(fault_resolve_path "${scenario_root}/opt/wallant/current")" == \
    "${scenario_root}/opt/wallant/releases/${fault_previous_sha}.20260805T000000Z.1" ]]
  grep -Fx 'old-unit' \
    "${scenario_root}/etc/systemd/system/wallant-v2-api.service" >/dev/null
  fault_assert_no_new_release "${scenario_root}"
}

fault_write_fake_commands
fault_build_archive

fault_run_scenario success 0 modern deployed
fault_run_scenario same-sha-success 0 same-sha deployed-same-sha
fault_run_scenario lock-cleanup-fail 1 modern deployed-lock-preserved
fault_run_scenario health-new-unreachable 1 modern rolled-back
fault_run_scenario health-new-unsafe 1 modern rolled-back
fault_run_scenario health-new-wrong-sha 1 modern rolled-back
fault_run_scenario duplicate-conflicting-safety 1 modern pre-switch-rejected
fault_run_scenario duplicate-whitespace-conflicting-safety 1 modern pre-switch-rejected
fault_run_scenario previous-unhealthy-stop-fail 1 modern previous-unhealthy-preserved
fault_run_scenario systemctl-restart-fail 1 modern rolled-back
fault_run_scenario signal-term 143 modern rolled-back
fault_run_scenario signal-hup 129 modern rolled-back
fault_run_scenario legacy-previous-health-fail 1 legacy rolled-back-legacy
fault_run_scenario rollback-health-fail 1 modern rollback-failed-closed
fault_run_scenario rollback-restart-fail 1 modern rollback-failed-closed
fault_run_scenario rollback-path-sha-mismatch 1 modern-mismatched rollback-failed-closed
fault_run_scenario first-deploy-health-fail 1 none first-deploy-stopped
fault_run_scenario first-deploy-stop-fail 1 none first-deploy-preserved
fault_run_scenario first-deploy-state-query-fail 1 none first-deploy-query-unverified
fault_run_preexisting_lock_case active-lock "$$"
fault_run_preexisting_lock_case stale-lock 999999

printf 'whole-script systemd, health, signal, rollback, first-deploy, and lock fault injection PASS\n'
