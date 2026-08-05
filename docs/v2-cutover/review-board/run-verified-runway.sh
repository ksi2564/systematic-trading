#!/usr/bin/env bash

set -euo pipefail

readonly qa_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly qa_verifier="${qa_script_dir}/verify-runway.mjs"
readonly qa_node_bin="$(type -P node)"
readonly qa_env_bin='/usr/bin/env'
[[ -x "${qa_node_bin}" ]] || { printf 'Node.js executable is unavailable\n' >&2; exit 1; }
[[ -x "${qa_env_bin}" ]] || { printf 'env executable is unavailable\n' >&2; exit 1; }

if [[ "${RUNWAY_BASE_URL+x}" == x ]]; then
  printf 'RUNWAY_BASE_URL is forbidden; QA owns its loopback server\n' >&2
  exit 1
fi
if [[ "${QA_RUNWAY_NETWORK_ISOLATION+x}" == x ]]; then
  printf 'QA_RUNWAY_NETWORK_ISOLATION is wrapper-owned and must not be preset\n' >&2
  exit 1
fi

case "$(/usr/bin/uname -s)" in
  Darwin)
    readonly qa_sandbox_bin='/usr/bin/sandbox-exec'
    [[ -x "${qa_sandbox_bin}" ]] || { printf 'sandbox-exec is unavailable\n' >&2; exit 1; }
    readonly qa_sandbox_profile='(version 1) (allow default) (deny network-outbound) (allow network-outbound (remote tcp "localhost:*"))'
    qa_darwin_environment=(
      "${qa_env_bin}" -i
      "HOME=${HOME}"
      "PATH=${PATH}"
      "QA_RUNWAY_NETWORK_ISOLATION=darwin-sandbox-exec"
    )
    [[ -z "${CI-}" ]] || qa_darwin_environment+=("CI=${CI}")
    [[ -z "${PLAYWRIGHT_BROWSERS_PATH-}" ]] || qa_darwin_environment+=("PLAYWRIGHT_BROWSERS_PATH=${PLAYWRIGHT_BROWSERS_PATH}")
    [[ -z "${TMPDIR-}" ]] || qa_darwin_environment+=("TMPDIR=${TMPDIR}")
    [[ -z "${LANG-}" ]] || qa_darwin_environment+=("LANG=${LANG}")
    exec "${qa_darwin_environment[@]}" \
      "${qa_sandbox_bin}" -p "${qa_sandbox_profile}" \
      "${qa_node_bin}" "${qa_verifier}"
    ;;
  Linux)
    readonly qa_unshare_bin='/usr/bin/unshare'
    readonly qa_ip_bin='/usr/sbin/ip'
    readonly qa_setpriv_bin='/usr/bin/setpriv'
    readonly qa_sudo_bin='/usr/bin/sudo'
    readonly qa_stat_bin='/usr/bin/stat'
    readonly qa_bash_bin='/bin/bash'
    for qa_system_binary in \
      "${qa_unshare_bin}" \
      "${qa_ip_bin}" \
      "${qa_setpriv_bin}" \
      "${qa_sudo_bin}" \
      "${qa_stat_bin}" \
      "${qa_env_bin}" \
      "${qa_bash_bin}"; do
      [[ -x "${qa_system_binary}" ]] || { printf 'required system binary is unavailable: %s\n' "${qa_system_binary}" >&2; exit 1; }
      read -r qa_binary_owner qa_binary_mode < <("${qa_stat_bin}" -L -c '%u %a' "${qa_system_binary}")
      [[ "${qa_binary_owner}" == 0 ]] || { printf 'system binary is not root-owned: %s\n' "${qa_system_binary}" >&2; exit 1; }
      (( (8#${qa_binary_mode} & 0022) == 0 )) || { printf 'system binary is group/other writable: %s\n' "${qa_system_binary}" >&2; exit 1; }
    done
    readonly qa_caller_uid="$(/usr/bin/id -u)"
    readonly qa_caller_gid="$(/usr/bin/id -g)"
    [[ "${qa_caller_uid}" != 0 ]] || { printf 'Linux QA-RWY must start as a non-root user\n' >&2; exit 1; }

    exec "${qa_sudo_bin}" -n "${qa_unshare_bin}" --net -- "${qa_env_bin}" -i "${qa_bash_bin}" -c '
      set -euo pipefail
      readonly qa_ip_bin="$1"
      readonly qa_setpriv_bin="$2"
      readonly qa_caller_uid="$3"
      readonly qa_caller_gid="$4"
      readonly qa_node_bin="$5"
      readonly qa_verifier="$6"
      readonly qa_user_home="$7"
      readonly qa_user_path="$8"
      readonly qa_ci="${9}"
      readonly qa_playwright_browsers_path="${10}"
      readonly qa_tmpdir="${11}"
      readonly qa_lang="${12}"

      "${qa_ip_bin}" link set lo up
      qa_environment=(
        /usr/bin/env -i
        "HOME=${qa_user_home}"
        "PATH=${qa_user_path}"
        "QA_RUNWAY_NETWORK_ISOLATION=linux-network-namespace"
      )
      [[ -z "${qa_ci}" ]] || qa_environment+=("CI=${qa_ci}")
      [[ -z "${qa_playwright_browsers_path}" ]] || qa_environment+=("PLAYWRIGHT_BROWSERS_PATH=${qa_playwright_browsers_path}")
      [[ -z "${qa_tmpdir}" ]] || qa_environment+=("TMPDIR=${qa_tmpdir}")
      [[ -z "${qa_lang}" ]] || qa_environment+=("LANG=${qa_lang}")

      exec "${qa_setpriv_bin}" \
        --reuid="${qa_caller_uid}" \
        --regid="${qa_caller_gid}" \
        --clear-groups \
        --bounding-set=-all \
        --inh-caps=-all \
        --ambient-caps=-all \
        --no-new-privs \
        "${qa_environment[@]}" \
        "${qa_node_bin}" "${qa_verifier}"
    ' qa-runway-netns \
      "${qa_ip_bin}" \
      "${qa_setpriv_bin}" \
      "${qa_caller_uid}" \
      "${qa_caller_gid}" \
      "${qa_node_bin}" \
      "${qa_verifier}" \
      "${HOME}" \
      "${PATH}" \
      "${CI-}" \
      "${PLAYWRIGHT_BROWSERS_PATH-}" \
      "${TMPDIR-}" \
      "${LANG-}"
    ;;
  *)
    printf 'QA-RWY requires a verified Darwin sandbox or Linux network namespace\n' >&2
    exit 1
    ;;
esac
