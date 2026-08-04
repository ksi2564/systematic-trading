#!/usr/bin/env bash
set -Eeuo pipefail

readonly allowed_emails_source="${1:-}"
readonly caddy_site_source="${2:-}"
readonly env_file="/etc/wallant/v2.env"
readonly caddyfile="/etc/caddy/Caddyfile"
readonly caddy_include_dir="/etc/caddy/conf.d"
readonly caddy_site="${caddy_include_dir}/wallant-v2.caddy"
readonly caddy_import='import /etc/caddy/conf.d/*.caddy'
readonly public_host="app.wall-ant.com"

backup_dir=""
site_existed=false
changes_started=false

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  rollback_on_error 1
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

rollback_on_error() {
  local exit_code="${1:-$?}"
  trap - ERR

  if [[ "${changes_started}" == true ]]; then
    cp -a "${backup_dir}/v2.env" "${env_file}"
    cp -a "${backup_dir}/Caddyfile" "${caddyfile}"
    if [[ "${site_existed}" == true ]]; then
      cp -a "${backup_dir}/wallant-v2.caddy" "${caddy_site}"
    else
      rm -f "${caddy_site}"
    fi
    caddy validate --config "${caddyfile}" || true
    systemctl reload caddy.service || true
    systemctl restart wallant-v2-api.service || true
    printf 'Private access origin configuration was rolled back.\n' >&2
  fi
  exit "${exit_code}"
}
trap 'rollback_on_error $?' ERR

[[ "${EUID}" -eq 0 ]] || fail "Run this script as root."
[[ -r "${allowed_emails_source}" ]] || fail "Allowed email configuration is missing."
[[ -r "${caddy_site_source}" ]] || fail "Caddy v2 site configuration is missing."
[[ -f "${env_file}" ]] || fail "v2 environment file is missing."
[[ -f "${caddyfile}" ]] || fail "Existing Caddyfile is missing."
[[ -f /opt/wallant/current/v2/frontend/dist/index.html ]] \
  || fail "Deployed v2 frontend build is missing."

for command_name in caddy curl getent grep install python3 ss systemctl; do
  command -v "${command_name}" >/dev/null 2>&1 || fail "Missing required command: ${command_name}"
done

grep -F "http://${public_host}:18082" "${caddy_site_source}" >/dev/null \
  || fail "Caddy v2 site must accept the public tunnel Host header."
grep -F 'bind 127.0.0.1' "${caddy_site_source}" >/dev/null \
  || fail "Caddy v2 site must remain bound to loopback."

getent group wallant >/dev/null 2>&1 || fail "Required wallant group is missing."
getent group caddy >/dev/null 2>&1 || fail "Required caddy group is missing."

validate_execution_safety_env "${env_file}" \
  || fail "Execution safety environment is missing, duplicated, or unsafe."
caddy validate --config "${caddyfile}"

if ss -lntp 2>/dev/null | awk '$4 ~ /:18082$/ && $0 !~ /caddy/ {found=1} END {exit(found ? 0 : 1)}'; then
  fail "Port 18082 is already owned by a process other than Caddy."
fi

allowed_email="$(python3 - "${allowed_emails_source}" <<'PY'
import json
import re
import sys

emails = json.load(open(sys.argv[1], encoding="utf-8"))
if not isinstance(emails, list) or not emails:
    raise SystemExit("At least one allowed email is required.")
normalized = []
for email in emails:
    if not isinstance(email, str):
        raise SystemExit("Allowed emails must be strings.")
    email = email.strip().lower()
    if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
        raise SystemExit("Allowed email is malformed.")
    if email not in normalized:
        normalized.append(email)
if not normalized:
    raise SystemExit("At least one allowed email is required.")
print(normalized[0])
PY
)"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="/var/backups/wallant/v2-access/${timestamp}-origin"
install -d -o root -g root -m 0700 "${backup_dir}"
cp -a "${env_file}" "${backup_dir}/v2.env"
cp -a "${caddyfile}" "${backup_dir}/Caddyfile"
if [[ -f "${caddy_site}" ]]; then
  site_existed=true
  cp -a "${caddy_site}" "${backup_dir}/wallant-v2.caddy"
fi
changes_started=true

python3 - "${env_file}" "${allowed_emails_source}" <<'PY'
import json
import os
from pathlib import Path
import sys

env_path = Path(sys.argv[1])
emails = []
for raw in json.load(open(sys.argv[2], encoding="utf-8")):
    email = raw.strip().lower()
    if email not in emails:
        emails.append(email)

updates = {
    "WALLANT_ENVIRONMENT": "production",
    "WALLANT_ALLOWED_ACCESS_EMAILS": json.dumps(emails, separators=(",", ":")),
}
seen = set()
lines = []
for line in env_path.read_text(encoding="utf-8").splitlines():
    key = line.split("=", 1)[0] if "=" in line else ""
    if key in updates:
        lines.append(f"{key}={updates[key]}")
        seen.add(key)
    else:
        lines.append(line)
for key, value in updates.items():
    if key not in seen:
        lines.append(f"{key}={value}")

temporary = env_path.with_suffix(".env.next")
temporary.write_text("\n".join(lines) + "\n", encoding="utf-8")
os.replace(temporary, env_path)
PY
chown root:wallant "${env_file}"
chmod 0640 "${env_file}"
validate_execution_safety_env "${env_file}" \
  || fail "Access configuration changed the execution safety environment."

install -d -o root -g caddy -m 0750 "${caddy_include_dir}"
install -o root -g caddy -m 0644 "${caddy_site_source}" "${caddy_site}.next"
mv -f "${caddy_site}.next" "${caddy_site}"
if ! grep -Fx "${caddy_import}" "${caddyfile}" >/dev/null; then
  printf '\n%s\n' "${caddy_import}" >>"${caddyfile}"
fi

caddy validate --config "${caddyfile}"
systemctl reload caddy.service
systemctl restart wallant-v2-api.service

for attempt in $(seq 1 30); do
  if systemctl is-active --quiet wallant-v2-api.service \
    && curl -fsS http://127.0.0.1:8000/health >/dev/null; then
    break
  fi
  if [[ "${attempt}" -eq 30 ]]; then
    systemctl status wallant-v2-api.service --no-pager || true
    journalctl -u wallant-v2-api.service -n 60 --no-pager || true
    fail "v2 API did not become healthy after enabling production access mode."
  fi
  sleep 2
done

unauthorized_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Host: ${public_host}" \
  http://127.0.0.1:18082/api/v2/operations/status)"
[[ "${unauthorized_status}" == 401 ]] \
  || fail "Unauthenticated origin API must return 401; got ${unauthorized_status}."

authorized_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Host: ${public_host}" \
  -H "Cf-Access-Authenticated-User-Email: ${allowed_email}" \
  http://127.0.0.1:18082/api/v2/operations/status)"
[[ "${authorized_status}" == 200 ]] \
  || fail "Allowed Access user must receive 200; got ${authorized_status}."

docs_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Host: ${public_host}" \
  -H "Cf-Access-Authenticated-User-Email: ${allowed_email}" \
  http://127.0.0.1:18082/api/v2/docs)"
[[ "${docs_status}" == 404 ]] \
  || fail "Production API docs must remain disabled; got ${docs_status}."

curl -fsS -H "Host: ${public_host}" http://127.0.0.1:18082/ \
  | grep -F '<div id="root"></div>' >/dev/null \
  || fail "v2 frontend was not served by the private Caddy origin."
ss -lnt 2>/dev/null | awk '$4 == "127.0.0.1:18082" {found=1} END {exit(found ? 0 : 1)}' \
  || fail "Caddy must listen on loopback 127.0.0.1:18082."

printf 'ACCESS_ORIGIN=http://127.0.0.1:18082\n'
printf 'ACCESS_AUTH=cloudflare-email-allowlist\n'
printf 'UNAUTHENTICATED_API_STATUS=%s\n' "${unauthorized_status}"
printf 'EXECUTION_SAFETY=disabled\n'
