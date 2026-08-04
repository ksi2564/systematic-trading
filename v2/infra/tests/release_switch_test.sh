#!/usr/bin/env bash
set -Eeuo pipefail

readonly test_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly deploy_script="${test_script_dir}/../scripts/deploy_staging.sh"
readonly configure_access_script="${test_script_dir}/../scripts/configure_access_staging.sh"
readonly staging_workflow="${test_script_dir}/../../../.github/workflows/v2-staging-deploy.yml"
readonly test_root_input="$(mktemp -d /tmp/wallant-release-switch.XXXXXX)"
readonly test_root="$(cd -- "${test_root_input}" && pwd -P)"

cleanup() {
  [[ "${test_root}" == /tmp/wallant-release-switch.* \
    || "${test_root}" == /private/tmp/wallant-release-switch.* ]] || return 1
  rm -rf -- "${test_root}"
}
trap cleanup EXIT

deploy_safety_validator_source="$(awk '
  /^validate_execution_safety_env\(\)/ { capture=1 }
  /^attest_wallant_v2_inactive\(\)/ { capture=0 }
  capture { print }
' "${deploy_script}")"
access_safety_validator_source="$(awk '
  /^validate_execution_safety_env\(\)/ { capture=1 }
  /^rollback_on_error\(\)/ { capture=0 }
  capture { print }
' "${configure_access_script}")"
[[ -n "${deploy_safety_validator_source}" ]]
[[ "${deploy_safety_validator_source}" == "${access_safety_validator_source}" ]]

(
  eval "${deploy_safety_validator_source}"
  safe_env="${test_root}/safe.env"
  printf '# comment\nUNRELATED=value\nWALLANT_EXECUTION_ENABLED=false\nWALLANT_BROKER_ADAPTER=disabled\n' \
    >"${safe_env}"
  validate_execution_safety_env "${safe_env}"

  invalid_env="${test_root}/invalid.env"
  invalid_cases=(
    $'WALLANT_EXECUTION_ENABLED=false\nWALLANT_EXECUTION_ENABLED=false\nWALLANT_BROKER_ADAPTER=disabled\n'
    $'WALLANT_EXECUTION_ENABLED=false\nWALLANT_EXECUTION_ENABLED=true\nWALLANT_BROKER_ADAPTER=disabled\n'
    $'WALLANT_EXECUTION_ENABLED=true\nWALLANT_BROKER_ADAPTER=disabled\n'
    $'WALLANT_EXECUTION_ENABLED=false\nWALLANT_BROKER_ADAPTER=kis-live\n'
    $'WALLANT_EXECUTION_ENABLED=false\n'
    $' WALLANT_EXECUTION_ENABLED=false\nWALLANT_BROKER_ADAPTER=disabled\n'
    $'WALLANT_EXECUTION_ENABLED=false\n WALLANT_EXECUTION_ENABLED=true\nWALLANT_BROKER_ADAPTER=disabled\n'
    $'WALLANT_EXECUTION_ENABLED=false\nWALLANT_BROKER_ADAPTER=disabled\n\tWALLANT_BROKER_ADAPTER=kis-live\n'
    $'WALLANT_EXECUTION_ENABLED=false\nWALLANT_EXECUTION_ENABLED =true\nWALLANT_BROKER_ADAPTER=disabled\n'
    $'WALLANT_EXECUTION_ENABLED=false\nWALLANT_BROKER_ADAPTER = kis-live\n'
  )
  for invalid_case in "${invalid_cases[@]}"; do
    printf '%s' "${invalid_case}" >"${invalid_env}"
    if validate_execution_safety_env "${invalid_env}" >/dev/null 2>&1; then
      printf 'strict safety environment parser accepted an invalid case\n' >&2
      exit 1
    fi
  done
)

lock_function_source="$(awk '
  /^acquire_deploy_lock\(\)/ { capture=1 }
  /^is_managed_release_path\(\)/ { capture=0 }
  capture { print }
' "${deploy_script}")"
[[ -n "${lock_function_source}" ]]

lock_path="${test_root}/deploy.lock"
ready_fifo="${test_root}/lock-ready.fifo"
release_fifo="${test_root}/lock-release.fifo"
mkfifo "${ready_fifo}" "${release_fifo}"
(
  deploy_lock_path="${lock_path}"
  deploy_lock_acquired=false
  eval "${lock_function_source}"
  acquire_deploy_lock
  printf 'ready\n' >"${ready_fifo}"
  IFS= read -r _ <"${release_fifo}"
  release_deploy_lock
) &
lock_holder_pid=$!
IFS= read -r _ <"${ready_fifo}"
if (
  deploy_lock_path="${lock_path}"
  deploy_lock_acquired=false
  eval "${lock_function_source}"
  acquire_deploy_lock 2>/dev/null
); then
  printf 'a concurrent deploy process acquired the host lock\n' >&2
  printf 'release\n' >"${release_fifo}"
  wait "${lock_holder_pid}"
  exit 1
fi
printf 'release\n' >"${release_fifo}"
wait "${lock_holder_pid}"

mkdir -m 0700 "${lock_path}"
printf '999999\n' >"${lock_path}/owner"
if (
  deploy_lock_path="${lock_path}"
  deploy_lock_acquired=false
  eval "${lock_function_source}"
  acquire_deploy_lock 2>/dev/null
); then
  printf 'a stale host lock was removed without manual review\n' >&2
  exit 1
fi
grep -Fx '999999' "${lock_path}/owner" >/dev/null
rm -f -- "${lock_path}/owner"
rmdir -- "${lock_path}"

signal_handler_source="$(awk '
  /^rollback_on_signal\(\)/ { capture=1 }
  /^trap '\''rollback_on_error/ { capture=0 }
  capture { print }
' "${deploy_script}")"
signal_trap_source="$(awk '/^trap '\''rollback_on_signal/ { print }' "${deploy_script}")"
[[ -n "${signal_handler_source}" && -n "${signal_trap_source}" ]]
signal_marker="${test_root}/signal-rollback.marker"
set +e
ROLLBACK_MARKER="${signal_marker}" \
SIGNAL_HANDLER_SOURCE="${signal_handler_source}" \
SIGNAL_TRAP_SOURCE="${signal_trap_source}" \
bash -c '
  set -u
  eval "${SIGNAL_HANDLER_SOURCE}"
  rollback_on_error() {
    printf "%s\n" "$1" >"${ROLLBACK_MARKER}"
    exit "$1"
  }
  eval "${SIGNAL_TRAP_SOURCE}"
  kill -TERM "$$"
  exit 99
' 2>/dev/null
signal_status=$?
set -e
[[ "${signal_status}" -eq 143 ]]
grep -Fx '143' "${signal_marker}" >/dev/null

replace_symlink_atomically() {
  python3 - "$1" "$2" <<'PY'
import os
import sys

os.replace(sys.argv[1], sys.argv[2])
PY
}

# The deployed script must create an immutable attempt path. Moving an active
# same-SHA directory before current is switched recreates the rollback bug.
grep -F 'release_dir="${release_root}/${release_sha}.${timestamp}.${BASHPID}"' \
  "${deploy_script}" >/dev/null
if grep -F 'mv "${release_dir}"' "${deploy_script}" >/dev/null; then
  printf 'deploy_staging.sh must not move an existing active release_dir\n' >&2
  exit 1
fi
python3 - "${deploy_script}" <<'PY'
from pathlib import Path
import sys

body = Path(sys.argv[1]).read_text(encoding="utf-8")


def assert_ordered(label: str, snippets: list[str]) -> None:
    positions = [body.find(snippet) for snippet in snippets]
    if -1 in positions or positions != sorted(positions) or len(set(positions)) != len(positions):
        raise SystemExit(f"{label} command order contract failed: {positions}")


assert_ordered(
    "main immutable switch",
    [
        'mv "${incoming_dir}" "${release_dir}"',
        'ln -sfn "${release_dir}" "${deploy_root}/current.next"',
        "current_switched=true",
        'mv -Tf "${deploy_root}/current.next" "${current_link}"',
        'raise SystemExit("deployed build SHA does not match the requested release")',
    ],
)
assert_ordered(
    "post-switch rollback",
    [
        'if [[ "${current_switched}" == true ]]; then',
        'ln -sfn "${previous_target}" "${deploy_root}/current.rollback"',
        'mv -Tf "${deploy_root}/current.rollback" "${current_link}"',
        'systemctl restart wallant-v2-api.service',
        'ROLLBACK_STATUS=verified previous=',
    ],
)
for required in [
    "acquire_deploy_lock()",
    'acquire_deploy_lock || fail "Could not acquire the host deployment lock."',
    "rollback_on_signal()",
    "trap 'rollback_on_signal HUP 129' HUP",
    "trap 'rollback_on_signal INT 130' INT",
    "trap 'rollback_on_signal TERM 143' TERM",
    "previous_target=\"\"",
    "current_switched=false",
    "readonly retain_recent_releases=3",
    "cleanup_failed_attempt()",
    "prune_completed_releases()",
    "is_managed_previous_release_path()",
    "on_exit()",
    "ROLLBACK_STATUS=verified-legacy",
    "FAILED_RELEASE_PRESERVED=",
    "trap 'rollback_on_error $?' ERR",
    'rollback_on_error 1',
]:
    if required not in body:
        raise SystemExit(f"missing rollback contract: {required}")

preserve_cleanup_call = body.find("    cleanup_failed_attempt false ||")
verified_cleanup_call = body.find("    cleanup_failed_attempt true ||")
lock_call = body.find('acquire_deploy_lock || fail "Could not acquire the host deployment lock."')
archive_hash = body.find('actual_archive_sha="$(sha256sum')
signal_handler = body.find("rollback_on_signal()")
signal_trap = body.find("trap 'rollback_on_signal TERM 143' TERM")
rollback_exit = body.rfind('  exit "${exit_code}"')
health_gate = body.rfind("systemctl is-active --quiet wallant-v2-api.service")
prune_call = body.rfind("if ! prune_completed_releases; then")
retention_output = body.rfind("RELEASE_RETENTION=%s")
if not (0 <= preserve_cleanup_call < verified_cleanup_call < rollback_exit):
    raise SystemExit("rollback cleanup must preserve completed bytes until recovery is verified")
if not (0 <= lock_call < archive_hash):
    raise SystemExit("host deploy lock must be acquired before archive processing")
if not (0 <= signal_handler < signal_trap < lock_call):
    raise SystemExit("deployment signals must be trapped before mutable deploy work")
if not (0 <= health_gate < prune_call < retention_output):
    raise SystemExit("release retention must run after the final health gate")
PY

python3 - "${staging_workflow}" <<'PY'
from pathlib import Path
import sys

body = Path(sys.argv[1]).read_text(encoding="utf-8")
step = body.index("      - name: Upload and deploy v2")
start = body.index("<<'REMOTE'", step)
end = body.index("      - name: Verify v2 isolation", start)
deploy = body[start:end]
required = [
    "          set -euo pipefail",
    '          trading_before_state="$(systemctl is-active trading.service',
    '          if [[ "${trading_before_state}" != active ]]; then',
    '          if [[ ! "${trading_before_pid}" =~ ^[0-9]+$ || "${trading_before_pid}" -le 0 ]]; then',
    "          set +e",
    '          bash "${deploy_script}"',
    '          trading_after_state="$(systemctl is-active trading.service',
    '          if [[ "${trading_after_state}" != active || "${trading_after_pid}" != "${trading_before_pid}" ]]',
]
positions = [deploy.find(item) for item in required]
if -1 in positions or positions != sorted(positions) or len(set(positions)) != len(positions):
    raise SystemExit(f"Java continuity precondition/order contract failed: {positions}")
PY

# Execute the exact remote shell body used by the deploy workflow. The inner
# deploy command is a fake here; deploy_staging_fault_injection_test.sh covers
# the deployment body itself. This verifies the Java continuity wrapper's
# precondition, postcondition, and exit-code propagation as executable shell.
workflow_deploy_wrapper="${test_root}/workflow-deploy-wrapper.sh"
awk '
  /^      - name: Upload and deploy v2$/ { in_step=1; next }
  in_step && /<<'\''REMOTE'\''$/ { capture=1; next }
  capture && /^          REMOTE$/ { exit }
  capture {
    sub(/^          /, "")
    print
  }
' "${staging_workflow}" >"${workflow_deploy_wrapper}"
grep -Fx 'set -euo pipefail' "${workflow_deploy_wrapper}" >/dev/null
grep -F 'trading_before_state=' "${workflow_deploy_wrapper}" >/dev/null
grep -F 'trading_after_pid=' "${workflow_deploy_wrapper}" >/dev/null

workflow_fake_bin="${test_root}/workflow-fake-bin"
mkdir -p "${workflow_fake_bin}"
cat >"${workflow_fake_bin}/systemctl" <<'SH'
#!/bin/bash
set -euo pipefail
case "${1:-}" in
  is-active)
    cat "${WORKFLOW_TRADING_STATE_FILE:?}"
    ;;
  show)
    cat "${WORKFLOW_TRADING_PID_FILE:?}"
    ;;
  *)
    printf 'unexpected workflow fake systemctl call: %s\n' "$*" >&2
    exit 2
    ;;
esac
SH
cat >"${workflow_fake_bin}/bash" <<'SH'
#!/bin/bash
set -euo pipefail
: >"${WORKFLOW_DEPLOY_CALLED_MARKER:?}"
printf '%s\n' "${WORKFLOW_AFTER_STATE:?}" >"${WORKFLOW_TRADING_STATE_FILE:?}"
printf '%s\n' "${WORKFLOW_AFTER_PID:?}" >"${WORKFLOW_TRADING_PID_FILE:?}"
exit "${WORKFLOW_DEPLOY_EXIT:?}"
SH
chmod 0755 "${workflow_fake_bin}/systemctl" "${workflow_fake_bin}/bash"

run_workflow_wrapper_case() {
  local case_name="$1"
  local before_state="$2"
  local before_pid="$3"
  local deploy_exit="$4"
  local after_state="$5"
  local after_pid="$6"
  local expected_exit="$7"
  local expect_deploy_call="$8"
  local case_root="${test_root}/workflow-${case_name}"
  local actual_exit=0

  mkdir -p "${case_root}"
  printf '%s\n' "${before_state}" >"${case_root}/state"
  printf '%s\n' "${before_pid}" >"${case_root}/pid"
  set +e
  PATH="${workflow_fake_bin}:${PATH}" \
  WORKFLOW_TRADING_STATE_FILE="${case_root}/state" \
  WORKFLOW_TRADING_PID_FILE="${case_root}/pid" \
  WORKFLOW_DEPLOY_CALLED_MARKER="${case_root}/deploy-called" \
  WORKFLOW_DEPLOY_EXIT="${deploy_exit}" \
  WORKFLOW_AFTER_STATE="${after_state}" \
  WORKFLOW_AFTER_PID="${after_pid}" \
    /bin/bash "${workflow_deploy_wrapper}" \
      /tmp/fake-deploy.sh /tmp/wallant-v2-fake.tgz \
      1111111111111111111111111111111111111111 \
      aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
      >"${case_root}/output.log" 2>&1
  actual_exit=$?
  set -e

  if [[ "${actual_exit}" -ne "${expected_exit}" ]]; then
    printf 'workflow wrapper case %s exited %s, expected %s\n' \
      "${case_name}" "${actual_exit}" "${expected_exit}" >&2
    sed -n '1,160p' "${case_root}/output.log" >&2
    return 1
  fi
  if [[ "${expect_deploy_call}" == true ]]; then
    if [[ ! -f "${case_root}/deploy-called" ]]; then
      printf 'workflow wrapper case %s did not invoke the deploy command\n' \
        "${case_name}" >&2
      sed -n '1,160p' "${case_root}/output.log" >&2
      return 1
    fi
  else
    if [[ -e "${case_root}/deploy-called" ]]; then
      printf 'workflow wrapper case %s invoked deploy before satisfying Java continuity preconditions\n' \
        "${case_name}" >&2
      sed -n '1,160p' "${case_root}/output.log" >&2
      return 1
    fi
  fi
}

run_workflow_wrapper_case stable-success active 4242 0 active 4242 0 true
run_workflow_wrapper_case inactive-precondition inactive 0 0 active 4242 1 false
run_workflow_wrapper_case zero-pid-precondition active 0 0 active 4242 1 false
run_workflow_wrapper_case deploy-error-propagated active 4242 17 active 4242 17 true
run_workflow_wrapper_case pid-changed active 4242 0 active 5252 1 true
run_workflow_wrapper_case state-changed active 4242 0 inactive 4242 1 true

# Execute the real cleanup/retention functions against an isolated release tree.
release_function_source="$(awk '
  /^is_managed_release_path\(\)/ { capture=1 }
  /^fail\(\)/ { capture=0 }
  capture { print }
' "${deploy_script}")"
(
  release_root="${test_root}/retention/releases"
  current_link="${test_root}/retention/current"
  retain_recent_releases=3
  previous_target=""
  release_dir=""
  incoming_dir=""
  eval "${release_function_source}"

  mkdir -p "${release_root}"
  release_1="${release_root}/1111111111111111111111111111111111111111.20260801T010101Z.101"
  release_2="${release_root}/2222222222222222222222222222222222222222.20260802T010101Z.102"
  release_3="${release_root}/3333333333333333333333333333333333333333.20260803T010101Z.103"
  release_4="${release_root}/4444444444444444444444444444444444444444.20260804T010101Z.104"
  release_5="${release_root}/5555555555555555555555555555555555555555.20260805T010101Z.105"
  unmanaged_release="${release_root}/manual-backup"
  mkdir -p "${release_1}" "${release_2}" "${release_3}" "${release_4}" "${release_5}" "${unmanaged_release}"
  ln -s "${release_5}" "${current_link}"
  previous_target="${release_2}"

  prune_completed_releases
  [[ ! -e "${release_1}" ]]
  [[ -d "${release_2}" && -d "${release_3}" && -d "${release_4}" && -d "${release_5}" ]]
  [[ -d "${unmanaged_release}" ]]

  release_dir="${release_root}/6666666666666666666666666666666666666666.20260806T010101Z.106"
  incoming_dir="${release_dir}.incoming"
  mkdir -p "${release_dir}" "${incoming_dir}"
  cleanup_failed_attempt true
  [[ ! -e "${release_dir}" && ! -e "${incoming_dir}" ]]

  release_dir="${release_root}/7777777777777777777777777777777777777777.20260807T010101Z.107"
  incoming_dir="${release_dir}.incoming"
  mkdir -p "${release_dir}" "${incoming_dir}"
  cleanup_failed_attempt false
  [[ -d "${release_dir}" && ! -e "${incoming_dir}" ]]
  rm -rf -- "${release_dir}"

  release_dir="${unmanaged_release}"
  incoming_dir=""
  if cleanup_failed_attempt 2>/dev/null; then
    printf 'cleanup must reject an unmanaged release path\n' >&2
    exit 1
  fi
  [[ -d "${unmanaged_release}" ]]
)

readonly release_sha="1111111111111111111111111111111111111111"
readonly release_root="${test_root}/releases"
readonly current_link="${test_root}/current"
readonly old_release="${release_root}/${release_sha}.old-attempt"
readonly new_release="${release_root}/${release_sha}.new-attempt"
mkdir -p "${old_release}" "${new_release}"
printf 'old-same-sha\n' >"${old_release}/marker"
printf 'new-same-sha\n' >"${new_release}/marker"
ln -s "${old_release}" "${current_link}"

# A failure before the atomic current swap must leave the previous target intact.
grep -Fx 'old-same-sha' "${current_link}/marker" >/dev/null
[[ "$(readlink -f "${current_link}")" == "${old_release}" ]]

# A failure after the swap (for example health mismatch) must restore old bytes,
# even though both attempts report the same build SHA.
previous_target="$(readlink -f "${current_link}")"
ln -sfn "${new_release}" "${test_root}/current.next"
replace_symlink_atomically "${test_root}/current.next" "${current_link}"
grep -Fx 'new-same-sha' "${current_link}/marker" >/dev/null

ln -sfn "${previous_target}" "${test_root}/current.rollback"
replace_symlink_atomically "${test_root}/current.rollback" "${current_link}"
[[ "$(readlink -f "${current_link}")" == "${old_release}" ]]
grep -Fx 'old-same-sha' "${current_link}/marker" >/dev/null

printf 'strict safety env, host lock, signal rollback, Java continuity, release retention, immutable same-SHA order contract, and rollback simulation PASS\n'
