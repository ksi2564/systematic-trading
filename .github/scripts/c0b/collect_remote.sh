#!/usr/bin/env bash
# Read-only C0-B collector.  This file is sent to `sudo bash -s` over SSH.
# Its stdout is a bounded protocol and must be piped directly to
# sanitize_capture.py; it must never be redirected to a raw file or artifact.

set -euo pipefail
export LC_ALL=C
umask 077

readonly ENV_FILE=/etc/trading/trading.env
readonly PROD_JAR=/opt/trading/app/trading.jar
readonly DASHBOARD_CURRENT=/opt/trading/admin-dashboard/current
readonly EXPECTED_UNIT_SHA256=4a2c8593bef542aea068b85d2a6b8b044ce2418aa7144cd82694d95863de6e3e
readonly SQL_LATEST_STATE="SELECT DATE_FORMAT(as_of_date,'%Y-%m-%d'), signal_symbol, CAST(strategy_on AS UNSIGNED), version, CONCAT('QQQM=',CAST(w_base AS CHAR),',QLD=',CAST(w_qld AS CHAR),',TQQQ=',CAST(w_tqqq AS CHAR)) FROM strategy_state ORDER BY as_of_date DESC, id DESC LIMIT 1"
readonly SQL_EOD_PAIR="SELECT CONCAT(DATE_FORMAT(s.as_of_date,'%Y-%m-%d'),',',s.signal_symbol,',',CAST(s.ath AS CHAR),',',CAST(s.last_close AS CHAR),',',CAST(s.drawdown_pct AS CHAR),',',CAST(s.max_drawdown_pct_since_ath AS CHAR),',',s.phase,',',s.dd_bucket,',',CAST(s.strategy_on AS UNSIGNED),',',s.version,',',CAST(s.w_base AS CHAR),',',CAST(s.w_qld AS CHAR),',',CAST(s.w_tqqq AS CHAR)),CAST(d.row_count AS UNSIGNED) FROM strategy_state s JOIN (SELECT as_of_date,MAX(id) AS id,COUNT(*) AS row_count FROM strategy_state GROUP BY as_of_date ORDER BY as_of_date DESC LIMIT 2) d ON d.id=s.id ORDER BY s.as_of_date DESC,s.id DESC"
readonly SQL_OPERATING_MODE="SELECT COALESCE((SELECT control_value FROM trading_control WHERE control_key='OPERATING_MODE' LIMIT 1),'NOT_PERSISTED')"
readonly SQL_KILL_SWITCH="SELECT COALESCE((SELECT UPPER(control_value) FROM trading_control WHERE control_key='KILL_SWITCH' LIMIT 1),'NOT_PERSISTED')"
readonly SQL_LATEST_JOB="SELECT COALESCE((SELECT CONCAT(DATE_FORMAT(signal_date,'%Y-%m-%d'),',',status) FROM execution_job ORDER BY signal_date DESC, execute_after DESC, id DESC LIMIT 1),'none')"
readonly SQL_OPEN_ORDER_COUNT="SELECT COUNT(*) FROM execution_order WHERE status IN ('PLANNED','REQUESTED','ACCEPTED','CONFIRMATION_REQUIRED')"
readonly REGISTRY_KEYS=(DD_BUCKET RECOVERY_RULE REBALANCE_TOLERANCE VIX_THRESHOLD MA_200_GUARD ORDER_BUFFER_RETRY_POLICY MAX_DAILY_TURNOVER_PCT MAX_ORDER_NOTIONAL_USD MAX_RETRY_EXPOSURE_USD MAX_SLIPPAGE_PCT)

readonly C0B_EXIT_PRECONDITION=41
readonly C0B_EXIT_RUNTIME=42
readonly C0B_EXIT_CONFIG=43
readonly C0B_EXIT_DB_INITIAL=44
readonly C0B_EXIT_DB_CONSISTENCY=45
readonly C0B_EXIT_HOST_STABILITY=46
readonly C0B_EXIT_PROTOCOL=47
collection_phase_exit=0

finish_with_phase_status() {
  local status=$?
  trap - EXIT
  if [[ "${status}" != 0 && "${collection_phase_exit}" -ge 41 && "${collection_phase_exit}" -le 47 ]]; then
    printf '%s\n' "C0-B remote collection failed at a fixed diagnostic phase" >&2
    exit "${collection_phase_exit}"
  fi
  exit "${status}"
}
trap finish_with_phase_status EXIT

die() {
  printf '%s\n' "C0-B remote collection rejected by safety guard" >&2
  exit 1
}

registry_select_sql() {
  local requested=$1
  local allowed
  for allowed in "${REGISTRY_KEYS[@]}"; do
    if [[ "${requested}" == "${allowed}" ]]; then
      printf "SELECT COALESCE((SELECT CONCAT(status,':',effective_value) FROM parameter_registry_record WHERE registry_key='%s' LIMIT 1),'MISSING')" "${requested}"
      return 0
    fi
  done
  return 1
}

validate_select_sql() {
  local sql=${1-}
  [[ -n "${sql}" ]] || return 1
  [[ "${sql}" != *$'\n'* && "${sql}" != *$'\r'* ]] || return 1
  [[ "${sql}" =~ ^[[:space:]]*SELECT[[:space:]] ]] || return 1
  [[ "${sql}" != *';'* && "${sql}" != *'--'* && "${sql}" != *'/*'* && "${sql}" != *'*/'* ]] || return 1
  local upper
  upper=$(printf '%s' "${sql}" | tr '[:lower:]' '[:upper:]')
  [[ ! "${upper}" =~ (^|[^A-Z])(INSERT|UPDATE|DELETE|REPLACE|MERGE|CALL|DO|SET|ALTER|CREATE|DROP|TRUNCATE|RENAME|GRANT|REVOKE|LOCK|UNLOCK|HANDLER|LOAD|INTO[[:space:]]+OUTFILE|INTO[[:space:]]+DUMPFILE|LOAD_FILE|SLEEP|BENCHMARK|GET_LOCK|RELEASE_LOCK|FOR[[:space:]]+UPDATE)([^A-Z]|$) ]] || return 1
  case "${sql}" in
    "${SQL_LATEST_STATE}"|"${SQL_EOD_PAIR}"|"${SQL_OPERATING_MODE}"|"${SQL_KILL_SWITCH}"|"${SQL_LATEST_JOB}"|"${SQL_OPEN_ORDER_COUNT}")
      return 0
      ;;
  esac
  local registry_key candidate
  for registry_key in "${REGISTRY_KEYS[@]}"; do
    candidate=$(registry_select_sql "${registry_key}") || return 1
    if [[ "${sql}" == "${candidate}" ]]; then
      return 0
    fi
  done
  return 1
}

decode_env_value() {
  local value=${1-}
  [[ -n "${value}" ]] || return 1
  local first=${value:0:1}
  local last=${value: -1}
  if [[ "${first}" == "'" || "${first}" == '"' ]]; then
    [[ "${last}" == "${first}" && ${#value} -ge 2 ]] || return 1
    value=${value:1:${#value}-2}
    [[ "${value}" != *"${first}"* ]] || return 1
  elif [[ "${last}" == "'" || "${last}" == '"' ]]; then
    return 1
  fi
  [[ "${value}" != *$'\n'* && "${value}" != *$'\r'* && "${value}" != *$'\t'* ]] || return 1
  printf '%s' "${value}"
}

read_env_file_value() {
  local source_file=$1
  local key=$2
  [[ -f "${source_file}" && ! -L "${source_file}" ]] || return 1
  [[ "${key}" =~ ^[A-Z_][A-Z0-9_]*$ ]] || return 1
  validate_env_file_shape "${source_file}" || return $?
  local encoded
  encoded=$(awk -v wanted="${key}" '
    BEGIN { found = 0 }
    index($0, wanted "=") == 1 {
      if (found) { exit 12 }
      value = substr($0, length(wanted) + 2)
      print value
      found = 1
    }
    END { if (!found) exit 11 }
  ' "${source_file}" 2>/dev/null) || return $?
  decode_env_value "${encoded}"
}

validate_env_file_shape() {
  local source_file=$1
  [[ -f "${source_file}" && ! -L "${source_file}" ]] || return 1
  awk '
    length($0) > 4096 || index($0, "\r") || index($0, "\t") { exit 13 }
    $0 == "" || $0 ~ /^#/ { next }
    $0 !~ /^[A-Z_][A-Z0-9_]*=/ || $0 ~ /\\$/ { exit 13 }
    {
      key = $0
      sub(/=.*/, "", key)
      if (++seen[key] > 1) { exit 14 }
    }
  ' "${source_file}" >/dev/null 2>&1
}

validate_prod_env_key_allowlist() {
  local source_file=$1
  [[ -f "${source_file}" && ! -L "${source_file}" ]] || return 1
  awk '
    BEGIN {
      allowed_text = "SPRING_PROFILES_ACTIVE SERVER_ADDRESS SERVER_PORT " \
        "SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD " \
        "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE " \
        "SPRING_DATASOURCE_HIKARI_MAX_LIFETIME_MS SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MS " \
        "SPRING_DATASOURCE_HIKARI_KEEPALIVE_TIME_MS SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT_MS " \
        "SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT_MS SPRING_FLYWAY_BASELINE_ON_MIGRATE " \
        "SPRING_FLYWAY_BASELINE_VERSION KIS_BASE_URL KIS_APP_KEY KIS_APP_SECRET KIS_ACCOUNT_NO " \
        "KIS_CANO KIS_ACNT_PRDT_CD KIS_CONNECT_TIMEOUT KIS_REQUEST_TIMEOUT REALTIME_QUOTE_ENABLED " \
        "TRADING_API_KEY TRADING_PUBLIC_READ_ALLOWED_ORIGINS TRADING_PUBLIC_CLIENT_IP_HEADER " \
        "TRADING_PUBLIC_TRUSTED_PROXY_RANGES TRADING_DISCORD_WEBHOOK_URL " \
        "TRADING_DISCORD_CONNECT_TIMEOUT TRADING_DISCORD_REQUEST_TIMEOUT " \
        "TRADING_SCHEDULING_ENABLED TRADING_EXECUTION_ENABLED TRADING_OPERATION_MODE " \
        "TRADING_FX_YAHOO_CONNECT_TIMEOUT TRADING_FX_YAHOO_REQUEST_TIMEOUT " \
        "TRADING_PRICING_MAX_ATTEMPTS TRADING_PRICING_RETRY_WAIT_MS " \
        "TRADING_STRATEGY_TOLERANCE_PCT TRADING_PRICING_TICK_SIZE " \
        "TRADING_CIRCUIT_BREAKER_MA_PERIOD BACKUP_DIR BACKUP_KEEP_DAYS BACKUP_CONFIG_DIR " \
        "MAC_BACKUP_ENABLED MAC_BACKUP_SSH_USER MAC_BACKUP_SSH_HOST MAC_BACKUP_SSH_PORT " \
        "MAC_BACKUP_SSH_KEY MAC_BACKUP_REMOTE_DIR"
      count = split(allowed_text, names, " ")
      for (i = 1; i <= count; i++) allowed[names[i]] = 1
    }
    $0 == "" || $0 ~ /^#/ { next }
    {
      key = $0
      sub(/=.*/, "", key)
      if (!(key in allowed)) exit 15
    }
  ' "${source_file}" >/dev/null 2>&1
}

if [[ "${1-}" == "--validate-select" ]]; then
  [[ "$#" == 2 ]] || exit 2
  validate_select_sql "$2"
  exit $?
fi
if [[ "${1-}" == "--decode-env-value" ]]; then
  [[ "$#" == 2 ]] || exit 2
  decode_env_value "$2"
  exit $?
fi
if [[ "${1-}" == "--self-test" ]]; then
  [[ "$#" == 1 ]] || exit 2
  validate_select_sql "${SQL_OPEN_ORDER_COUNT}"
  ! validate_select_sql "SELECT 1"
  ! validate_select_sql "SELECT * FROM mysql.user"
  ! validate_select_sql "UPDATE strategy_state SET version=2"
  [[ $(decode_env_value "'prod'") == prod ]]
  exit 0
fi
if [[ "${1-}" == "--validate-env-file" ]]; then
  [[ "$#" == 3 ]] || exit 2
  read_env_file_value "$2" "$3" >/dev/null
  exit $?
fi
if [[ "${1-}" == "--validate-prod-env-file" ]]; then
  [[ "$#" == 2 ]] || exit 2
  validate_env_file_shape "$2" && validate_prod_env_key_allowlist "$2"
  exit $?
fi

collection_phase_exit=${C0B_EXIT_PRECONDITION}
trap 'exit "${collection_phase_exit}"' PIPE
[[ "$#" == 0 ]] || die
[[ "${EUID}" == 0 ]] || die
[[ -r "${ENV_FILE}" && -f "${ENV_FILE}" && ! -L "${ENV_FILE}" && -f "${PROD_JAR}" && ! -L "${PROD_JAR}" ]] || die
validate_env_file_shape "${ENV_FILE}" || die
validate_prod_env_key_allowlist "${ENV_FILE}" || die
command -v mysql >/dev/null 2>&1 || die
command -v systemctl >/dev/null 2>&1 || die
command -v sha256sum >/dev/null 2>&1 || die
command -v stat >/dev/null 2>&1 || die
command -v date >/dev/null 2>&1 || die
env_stat_before=$(stat --format='%Y:%s' "${ENV_FILE}" 2>/dev/null) || die
env_sha_before=$(sha256sum "${ENV_FILE}" 2>/dev/null | awk '{print $1}') || die
jar_stat_before=$(stat --format='%Y:%s' "${PROD_JAR}" 2>/dev/null) || die
readonly env_stat_before env_sha_before jar_stat_before

emit() {
  local item=$1
  local name=$2
  local value=$3
  [[ -n "${value}" && ${#value} -le 500 ]] || die
  [[ "${item}${name}${value}" != *$'\t'* && "${item}${name}${value}" != *$'\n'* && "${item}${name}${value}" != *$'\r'* ]] || die
  printf '%s\t%s\t%s\n' "${item}" "${name}" "${value}"
}

read_env_raw() {
  local key=$1
  read_env_file_value "${ENV_FILE}" "${key}"
}

safe_config_value() {
  local key=$1
  local fallback=$2
  local value
  case "${key}" in
    SPRING_PROFILES_ACTIVE|TRADING_SCHEDULING_ENABLED|TRADING_EXECUTION_ENABLED|TRADING_OPERATION_MODE|SERVER_ADDRESS|SERVER_PORT|KIS_BASE_URL|KIS_CONNECT_TIMEOUT|KIS_REQUEST_TIMEOUT|TRADING_FX_YAHOO_CONNECT_TIMEOUT|TRADING_FX_YAHOO_REQUEST_TIMEOUT|TRADING_PRICING_MAX_ATTEMPTS|TRADING_PRICING_RETRY_WAIT_MS|TRADING_STRATEGY_TOLERANCE_PCT|TRADING_PRICING_TICK_SIZE|TRADING_CIRCUIT_BREAKER_MA_PERIOD)
      ;;
    *)
      die
      ;;
  esac
  if value=$(read_env_raw "${key}"); then
    :
  else
    local status=$?
    [[ "${status}" == 11 ]] || die
    value=${fallback}
  fi
  [[ "${value}" =~ ^[A-Za-z0-9._:/,+-]+$ ]] || die
  printf '%s' "${value}"
}

env_override_count=$(awk -F= '
  /^(SPRING_APPLICATION_JSON|SPRING_CONFIG_(NAME|LOCATION|ADDITIONAL_LOCATION|IMPORT)|SPRING_PROFILES_(INCLUDE|DEFAULT)|SPRING_PROFILES_GROUP_[A-Z0-9_]+|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS)=/ { count++ }
  END { print count + 0 }
' "${ENV_FILE}" 2>/dev/null) || die
decision_config_override_count=$(awk -F= '
  {
    key = $1
    compact = key
    gsub(/_/, "", compact)
    if (compact ~ /^(TRADINGSTRATEGY|TRADINGPRICING|TRADINGCIRCUITBREAKER|TRADINGOPERATION|TRADINGMARKETCALENDAR)/ &&
        key != "TRADING_STRATEGY_TOLERANCE_PCT" &&
        key != "TRADING_PRICING_MAX_ATTEMPTS" &&
        key != "TRADING_PRICING_RETRY_WAIT_MS" &&
        key != "TRADING_PRICING_TICK_SIZE" &&
        key != "TRADING_CIRCUIT_BREAKER_MA_PERIOD" &&
        key != "TRADING_OPERATION_MODE") {
      count++
    }
    if (key == "REALTIME_QUOTE_ENABLED") {
      count++
    }
  }
  END { print count + 0 }
' "${ENV_FILE}" 2>/dev/null) || die
env_override_contract=EXPECTED
if [[ "${env_override_count}" != 0 ]]; then
  env_override_contract=UNVERIFIED_OVERRIDE_PRESENT
fi
decision_config_override_contract=EXPECTED
if [[ "${decision_config_override_count}" != 0 ]]; then
  decision_config_override_contract=UNVERIFIED_OVERRIDE_PRESENT
fi
readonly env_override_count decision_config_override_count env_override_contract
readonly decision_config_override_contract

DB_URL=$(read_env_raw SPRING_DATASOURCE_URL) || die
DB_USER=$(read_env_raw SPRING_DATASOURCE_USERNAME) || die
DB_PASSWORD=$(read_env_raw SPRING_DATASOURCE_PASSWORD) || die
readonly DB_URL DB_USER DB_PASSWORD
if [[ ! "${DB_URL}" =~ ^jdbc:mysql://(localhost|127\.0\.0\.1):([0-9]{1,5})/([A-Za-z0-9_]+)(\?.*)?$ ]]; then
  die
fi
readonly DB_HOST=${BASH_REMATCH[1]}
readonly DB_PORT=${BASH_REMATCH[2]}
readonly DB_NAME=${BASH_REMATCH[3]}
[[ "${DB_PORT}" -ge 1 && "${DB_PORT}" -le 65535 ]] || die
[[ -n "${DB_USER}" && -n "${DB_PASSWORD}" ]] || die

mysql_select() {
  local sql=$1
  validate_select_sql "${sql}" || die
  MYSQL_PWD="${DB_PASSWORD}" mysql --no-defaults --batch --skip-column-names --raw \
    --init-command='SET SESSION TRANSACTION READ ONLY' \
    --connect-timeout=5 --host="${DB_HOST}" --port="${DB_PORT}" \
    --user="${DB_USER}" --database="${DB_NAME}" --execute="${sql}" 2>/dev/null
}

collection_phase_exit=${C0B_EXIT_RUNTIME}
service_load_state=$(systemctl show trading --property=LoadState --value 2>/dev/null) || die
readonly service_load_state
[[ "${service_load_state}" != not-found && -n "${service_load_state}" ]] || die
service_active=$(systemctl show trading --property=ActiveState --value 2>/dev/null) || die
readonly service_active
case "${service_active}" in
  active|reloading|inactive|failed|activating|deactivating|maintenance|refreshing) ;;
  *) die ;;
esac
service_sub_state=$(systemctl show trading --property=SubState --value 2>/dev/null) || die
readonly service_sub_state
[[ "${service_sub_state}" =~ ^[a-z-]+$ ]] || die
service_main_pid=$(systemctl show trading --property=MainPID --value 2>/dev/null) || die
readonly service_main_pid
[[ "${service_main_pid}" =~ ^[0-9]{1,10}$ ]] || die
if [[ "${service_active}" == active || "${service_active}" == reloading ]]; then
  [[ "${service_main_pid}" -gt 0 ]] || die
fi
service_fragment_path=$(systemctl show trading --property=FragmentPath --value 2>/dev/null) || die
service_drop_in_paths=$(systemctl show trading --property=DropInPaths --value 2>/dev/null) || die
service_environment_files=$(systemctl show trading --property=EnvironmentFiles --value 2>/dev/null) || die
service_exec_start=$(systemctl show trading --property=ExecStart --value 2>/dev/null) || die
service_working_directory=$(systemctl show trading --property=WorkingDirectory --value 2>/dev/null) || die
readonly service_fragment_path service_drop_in_paths service_environment_files service_exec_start
readonly service_working_directory
unit_fragment_contract=UNVERIFIED_OVERRIDE_PRESENT
unit_stat_before=MISSING
if [[ "${service_fragment_path}" == /etc/systemd/system/trading.service && -f "${service_fragment_path}" && ! -L "${service_fragment_path}" ]]; then
  installed_unit_sha=$(sha256sum "${service_fragment_path}" 2>/dev/null | awk '{print $1}') || die
  unit_stat_before=$(stat --format='%Y:%s' "${service_fragment_path}" 2>/dev/null) || die
  if [[ "${installed_unit_sha}" == "${EXPECTED_UNIT_SHA256}" ]]; then
    unit_fragment_contract=EXPECTED
  fi
fi
readonly unit_stat_before
drop_in_contract=UNVERIFIED_OVERRIDE_PRESENT
if [[ -z "${service_drop_in_paths}" ]]; then
  drop_in_contract=EXPECTED
fi
environment_file_contract=$(
  if [[ "${service_environment_files}" == "/etc/trading/trading.env" || "${service_environment_files}" == "/etc/trading/trading.env (ignore_errors=no)" ]]; then
    printf EXPECTED
  else
    printf UNVERIFIED_OVERRIDE_PRESENT
  fi
) || die
exec_start_contract=$(
  if [[ "${service_exec_start}" == "/usr/bin/java -Xms256m -Xmx1024m -jar /opt/trading/app/trading.jar" || "${service_exec_start}" == "{ path=/usr/bin/java ; argv[]=/usr/bin/java -Xms256m -Xmx1024m -jar /opt/trading/app/trading.jar ;"* ]]; then
    printf EXPECTED
  else
    printf UNVERIFIED_OVERRIDE_PRESENT
  fi
) || die
working_directory_contract=UNVERIFIED_OVERRIDE_PRESENT
if [[ "${service_working_directory}" == /opt/trading/app ]]; then
  working_directory_contract=EXPECTED
fi
external_config_contract=EXPECTED
shopt -s nullglob
external_config_candidates=(
  /opt/trading/app/application*.properties
  /opt/trading/app/application*.yml
  /opt/trading/app/application*.yaml
  /opt/trading/app/config/application*.properties
  /opt/trading/app/config/application*.yml
  /opt/trading/app/config/application*.yaml
  /opt/trading/app/config/*/application*.properties
  /opt/trading/app/config/*/application*.yml
  /opt/trading/app/config/*/application*.yaml
)
shopt -u nullglob
if (( ${#external_config_candidates[@]} != 0 )); then
  external_config_contract=UNVERIFIED_OVERRIDE_PRESENT
fi
external_config_presence_before=${#external_config_candidates[@]}
readonly external_config_contract working_directory_contract
readonly external_config_presence_before
runtime_config_basis="next start configuration only"
runtime_code_basis="service not running"
service_start_text=not-running
if [[ "${service_active}" == active || "${service_active}" == reloading ]]; then
  runtime_config_basis="timestamp comparison unverified"
  runtime_code_basis="timestamp comparison unverified"
  service_start_text=$(systemctl show trading --property=ExecMainStartTimestamp --value 2>/dev/null) || die
  if service_start_epoch=$(date --date="${service_start_text}" +%s 2>/dev/null); then
    env_mtime=$(stat --format=%Y "${ENV_FILE}" 2>/dev/null) || die
    jar_mtime=$(stat --format=%Y "${PROD_JAR}" 2>/dev/null) || die
    unit_mtime=0
    if [[ "${service_fragment_path}" == /etc/systemd/system/trading.service && -f "${service_fragment_path}" && ! -L "${service_fragment_path}" ]]; then
      unit_mtime=$(stat --format=%Y "${service_fragment_path}" 2>/dev/null) || die
    fi
    if [[ "${jar_mtime}" -lt "${service_start_epoch}" ]]; then
      runtime_code_basis="running process jar predates start"
    else
      runtime_code_basis="disk jar changed or timestamp ambiguous"
    fi
    if [[ "${env_mtime}" -gt "${service_start_epoch}" || "${unit_mtime}" -gt "${service_start_epoch}" || "${jar_mtime}" -ge "${service_start_epoch}" ]]; then
      runtime_config_basis="running process files changed since start"
    else
      runtime_config_basis="running process matches file timestamps"
    fi
  fi
fi
readonly service_start_text
effective_override_status=UNVERIFIED_OVERRIDE_PRESENT
if [[ "${unit_fragment_contract}" == EXPECTED && "${drop_in_contract}" == EXPECTED && "${environment_file_contract}" == EXPECTED && "${exec_start_contract}" == EXPECTED && "${working_directory_contract}" == EXPECTED && "${external_config_contract}" == EXPECTED && "${env_override_contract}" == EXPECTED && "${decision_config_override_contract}" == EXPECTED && "${runtime_config_basis}" != "running process files changed since start" && "${runtime_config_basis}" != "timestamp comparison unverified" ]]; then
  effective_override_status=NO_UNVERIFIED_OVERRIDE
fi
readonly unit_fragment_contract drop_in_contract environment_file_contract exec_start_contract
readonly runtime_config_basis runtime_code_basis effective_override_status
release_path=$(readlink -f "${DASHBOARD_CURRENT}" 2>/dev/null) || die
readonly release_path
release_sha=${release_path##*/}
readonly release_sha
[[ "${release_sha}" =~ ^[0-9a-f]{40}$ ]] || die
jar_sha=$(sha256sum "${PROD_JAR}" 2>/dev/null | awk '{print $1}') || die
readonly jar_sha
[[ "${jar_sha}" =~ ^[0-9a-f]{64}$ ]] || die

collection_phase_exit=${C0B_EXIT_CONFIG}
spring_profile=$(safe_config_value SPRING_PROFILES_ACTIVE default) || die
spring_profile=$(printf '%s' "${spring_profile}" | tr '[:upper:]' '[:lower:]') || die
default_scheduling_enabled=false
if [[ "${spring_profile}" == prod ]]; then
  default_scheduling_enabled=true
fi
readonly default_scheduling_enabled
scheduling_enabled=$(safe_config_value TRADING_SCHEDULING_ENABLED "${default_scheduling_enabled}") || die
execution_enabled=$(safe_config_value TRADING_EXECUTION_ENABLED false) || die
operation_mode=$(safe_config_value TRADING_OPERATION_MODE PAPER) || die
scheduling_enabled=$(printf '%s' "${scheduling_enabled}" | tr '[:upper:]' '[:lower:]') || die
execution_enabled=$(printf '%s' "${execution_enabled}" | tr '[:upper:]' '[:lower:]') || die
operation_mode=$(printf '%s' "${operation_mode}" | tr '[:lower:]' '[:upper:]') || die
server_port=$(safe_config_value SERVER_PORT 8080) || die
server_address=UNVERIFIED_NON_PROD_PROFILE
if explicit_server_address=$(read_env_raw SERVER_ADDRESS); then
  [[ "${explicit_server_address}" =~ ^(127\.0\.0\.1|localhost)$ ]] || die
  server_address=${explicit_server_address}
else
  address_status=$?
  [[ "${address_status}" == 11 ]] || die
  if [[ "${spring_profile}" == prod ]]; then
    server_address=127.0.0.1
  fi
fi
connect_timeout=$(safe_config_value KIS_CONNECT_TIMEOUT 3s) || die
request_timeout=$(safe_config_value KIS_REQUEST_TIMEOUT 10s) || die
kis_origin=$(safe_config_value KIS_BASE_URL https://openapi.koreainvestment.com:9443) || die
kis_origin_contract=UNEXPECTED
if [[ "${kis_origin}" == https://openapi.koreainvestment.com:9443 ]]; then
  kis_origin_contract=EXPECTED_PRODUCTION
fi
yahoo_connect_timeout=$(safe_config_value TRADING_FX_YAHOO_CONNECT_TIMEOUT 3s) || die
yahoo_request_timeout=$(safe_config_value TRADING_FX_YAHOO_REQUEST_TIMEOUT 5s) || die
max_attempts=$(safe_config_value TRADING_PRICING_MAX_ATTEMPTS 3) || die
retry_wait_ms=$(safe_config_value TRADING_PRICING_RETRY_WAIT_MS 2000) || die
tolerance_pct=$(safe_config_value TRADING_STRATEGY_TOLERANCE_PCT 5.0) || die
tick_size=$(safe_config_value TRADING_PRICING_TICK_SIZE 0.01) || die
ma_period=$(safe_config_value TRADING_CIRCUIT_BREAKER_MA_PERIOD 200) || die
readonly spring_profile scheduling_enabled execution_enabled operation_mode
readonly server_address server_port connect_timeout request_timeout kis_origin kis_origin_contract
readonly yahoo_connect_timeout yahoo_request_timeout max_attempts retry_wait_ms
readonly tolerance_pct tick_size ma_period

collection_phase_exit=${C0B_EXIT_DB_INITIAL}
readonly latest_sql=${SQL_LATEST_STATE}
latest_row=$(mysql_select "${latest_sql}") || die
readonly latest_row
[[ "${latest_row}" != *$'\n'* ]] || die
latest_date=MISSING
latest_symbol=MISSING
latest_on=MISSING
latest_version=MISSING
latest_weights=MISSING
if [[ -n "${latest_row}" ]]; then
  IFS=$'\t' read -r latest_date latest_symbol latest_on latest_version latest_weights <<<"${latest_row}"
  [[ -n "${latest_date}" && -n "${latest_symbol}" && -n "${latest_on}" && -n "${latest_version}" && -n "${latest_weights}" ]] || die
fi
readonly latest_date latest_symbol latest_on latest_version latest_weights

registry_value() {
  local registry_key=$1
  local sql
  sql=$(registry_select_sql "${registry_key}") || die
  mysql_select "${sql}"
}

dd_bucket_parameter=$(registry_value DD_BUCKET) || die
recovery_rule_parameter=$(registry_value RECOVERY_RULE) || die
rebalance_tolerance_parameter=$(registry_value REBALANCE_TOLERANCE) || die
vix_threshold_parameter=$(registry_value VIX_THRESHOLD) || die
ma_200_guard_parameter=$(registry_value MA_200_GUARD) || die
order_buffer_retry_policy_parameter=$(registry_value ORDER_BUFFER_RETRY_POLICY) || die
max_daily_turnover_parameter=$(registry_value MAX_DAILY_TURNOVER_PCT) || die
max_order_notional_parameter=$(registry_value MAX_ORDER_NOTIONAL_USD) || die
max_retry_exposure_parameter=$(registry_value MAX_RETRY_EXPOSURE_USD) || die
max_slippage_parameter=$(registry_value MAX_SLIPPAGE_PCT) || die
readonly dd_bucket_parameter recovery_rule_parameter rebalance_tolerance_parameter
readonly vix_threshold_parameter ma_200_guard_parameter order_buffer_retry_policy_parameter
readonly max_daily_turnover_parameter max_order_notional_parameter
readonly max_retry_exposure_parameter max_slippage_parameter

readonly state_pair_sql=${SQL_EOD_PAIR}
state_pair=$(mysql_select "${state_pair_sql}") || die
readonly state_pair
state_count=$(printf '%s\n' "${state_pair}" | awk 'NF { count++ } END { print count + 0 }') || die
readonly state_count
[[ "${state_count}" -le 2 ]] || die
latest_state=MISSING
latest_date_row_count=0
previous_state=MISSING
previous_date_row_count=0
if [[ "${state_count}" -ge 1 ]]; then
  latest_pair_line=$(printf '%s\n' "${state_pair}" | sed -n '1p')
  IFS=$'\t' read -r latest_state latest_date_row_count <<<"${latest_pair_line}"
  [[ -n "${latest_state}" && "${latest_date_row_count}" =~ ^[0-9]{1,7}$ ]] || die
fi
if [[ "${state_count}" -eq 2 ]]; then
  previous_pair_line=$(printf '%s\n' "${state_pair}" | sed -n '2p')
  IFS=$'\t' read -r previous_state previous_date_row_count <<<"${previous_pair_line}"
  [[ -n "${previous_state}" && "${previous_date_row_count}" =~ ^[0-9]{1,7}$ ]] || die
fi
readonly latest_state latest_date_row_count previous_state previous_date_row_count
if [[ "${latest_date}" == MISSING ]]; then
  [[ "${latest_state}" == MISSING ]] || die
else
  IFS=',' read -r pair_date pair_symbol pair_ath pair_close pair_drawdown pair_max_drawdown pair_phase pair_bucket pair_on pair_version pair_w_base pair_w_qld pair_w_tqqq <<<"${latest_state}"
  [[ -n "${pair_w_tqqq}" ]] || die
  pair_weights="QQQM=${pair_w_base},QLD=${pair_w_qld},TQQQ=${pair_w_tqqq}"
  [[ "${latest_date}" == "${pair_date}" && "${latest_symbol}" == "${pair_symbol}" && "${latest_on}" == "${pair_on}" && "${latest_version}" == "${pair_version}" && "${latest_weights}" == "${pair_weights}" ]] || die
fi

readonly mode_sql=${SQL_OPERATING_MODE}
db_mode=$(mysql_select "${mode_sql}") || die
readonly db_mode
effective_mode=${db_mode}
effective_mode_source="database record"
effective_mode_verification=VERIFIED_DATABASE_RECORD
if [[ "${db_mode}" == NOT_PERSISTED ]]; then
  effective_mode=${operation_mode}
  effective_mode_source="current environment-file fallback"
  effective_mode_verification=UNVERIFIED_LIVE_PROCESS_ENVIRONMENT
fi
case "${effective_mode}" in
  PAPER|MANUAL_LIVE|AUTO_LIVE) ;;
  *) die ;;
esac
readonly effective_mode effective_mode_source effective_mode_verification
readonly kill_switch_sql=${SQL_KILL_SWITCH}
kill_switch_db=$(mysql_select "${kill_switch_sql}") || die
case "${kill_switch_db}" in
  ON|OFF|NOT_PERSISTED) ;;
  *) die ;;
esac
readonly kill_switch_db
submission_guard_derivation=ALLOWED_BY_LOCAL_GUARD
if [[ "${kill_switch_db}" == ON ]]; then
  submission_guard_derivation=BLOCKED_KILL_SWITCH
elif [[ "${effective_mode}" == PAPER ]]; then
  submission_guard_derivation=BLOCKED_PAPER_MODE
elif [[ "${execution_enabled}" != true ]]; then
  submission_guard_derivation=BLOCKED_EXECUTION_DISABLED
fi
readonly submission_guard_derivation
readonly latest_job_sql=${SQL_LATEST_JOB}
latest_job=$(mysql_select "${latest_job_sql}") || die
readonly latest_job
readonly open_count_sql=${SQL_OPEN_ORDER_COUNT}
open_order_count=$(mysql_select "${open_count_sql}") || die
readonly open_order_count

collection_phase_exit=${C0B_EXIT_DB_CONSISTENCY}
latest_row_after=$(mysql_select "${latest_sql}") || die
state_pair_after=$(mysql_select "${state_pair_sql}") || die
dd_bucket_parameter_after=$(registry_value DD_BUCKET) || die
recovery_rule_parameter_after=$(registry_value RECOVERY_RULE) || die
rebalance_tolerance_parameter_after=$(registry_value REBALANCE_TOLERANCE) || die
vix_threshold_parameter_after=$(registry_value VIX_THRESHOLD) || die
ma_200_guard_parameter_after=$(registry_value MA_200_GUARD) || die
order_buffer_retry_policy_parameter_after=$(registry_value ORDER_BUFFER_RETRY_POLICY) || die
max_daily_turnover_parameter_after=$(registry_value MAX_DAILY_TURNOVER_PCT) || die
max_order_notional_parameter_after=$(registry_value MAX_ORDER_NOTIONAL_USD) || die
max_retry_exposure_parameter_after=$(registry_value MAX_RETRY_EXPOSURE_USD) || die
max_slippage_parameter_after=$(registry_value MAX_SLIPPAGE_PCT) || die
db_mode_after=$(mysql_select "${mode_sql}") || die
kill_switch_db_after=$(mysql_select "${kill_switch_sql}") || die
latest_job_after=$(mysql_select "${latest_job_sql}") || die
open_order_count_after=$(mysql_select "${open_count_sql}") || die
[[ "${latest_row}" == "${latest_row_after}" && "${state_pair}" == "${state_pair_after}" ]] || die
[[ "${dd_bucket_parameter}" == "${dd_bucket_parameter_after}" ]] || die
[[ "${recovery_rule_parameter}" == "${recovery_rule_parameter_after}" ]] || die
[[ "${rebalance_tolerance_parameter}" == "${rebalance_tolerance_parameter_after}" ]] || die
[[ "${vix_threshold_parameter}" == "${vix_threshold_parameter_after}" ]] || die
[[ "${ma_200_guard_parameter}" == "${ma_200_guard_parameter_after}" ]] || die
[[ "${order_buffer_retry_policy_parameter}" == "${order_buffer_retry_policy_parameter_after}" ]] || die
[[ "${max_daily_turnover_parameter}" == "${max_daily_turnover_parameter_after}" ]] || die
[[ "${max_order_notional_parameter}" == "${max_order_notional_parameter_after}" ]] || die
[[ "${max_retry_exposure_parameter}" == "${max_retry_exposure_parameter_after}" ]] || die
[[ "${max_slippage_parameter}" == "${max_slippage_parameter_after}" ]] || die
[[ "${db_mode}" == "${db_mode_after}" && "${kill_switch_db}" == "${kill_switch_db_after}" ]] || die
[[ "${latest_job}" == "${latest_job_after}" && "${open_order_count}" == "${open_order_count_after}" ]] || die
readonly latest_row_after state_pair_after dd_bucket_parameter_after
readonly recovery_rule_parameter_after rebalance_tolerance_parameter_after
readonly vix_threshold_parameter_after ma_200_guard_parameter_after
readonly order_buffer_retry_policy_parameter_after max_daily_turnover_parameter_after
readonly max_order_notional_parameter_after max_retry_exposure_parameter_after
readonly max_slippage_parameter_after db_mode_after kill_switch_db_after
readonly latest_job_after open_order_count_after

collection_phase_exit=${C0B_EXIT_HOST_STABILITY}
env_stat_after=$(stat --format='%Y:%s' "${ENV_FILE}" 2>/dev/null) || die
env_sha_after=$(sha256sum "${ENV_FILE}" 2>/dev/null | awk '{print $1}') || die
jar_stat_after=$(stat --format='%Y:%s' "${PROD_JAR}" 2>/dev/null) || die
jar_sha_after=$(sha256sum "${PROD_JAR}" 2>/dev/null | awk '{print $1}') || die
release_path_after=$(readlink -f "${DASHBOARD_CURRENT}" 2>/dev/null) || die
service_active_after=$(systemctl show trading --property=ActiveState --value 2>/dev/null) || die
service_sub_state_after=$(systemctl show trading --property=SubState --value 2>/dev/null) || die
service_main_pid_after=$(systemctl show trading --property=MainPID --value 2>/dev/null) || die
service_fragment_path_after=$(systemctl show trading --property=FragmentPath --value 2>/dev/null) || die
service_drop_in_paths_after=$(systemctl show trading --property=DropInPaths --value 2>/dev/null) || die
service_environment_files_after=$(systemctl show trading --property=EnvironmentFiles --value 2>/dev/null) || die
service_exec_start_after=$(systemctl show trading --property=ExecStart --value 2>/dev/null) || die
service_working_directory_after=$(systemctl show trading --property=WorkingDirectory --value 2>/dev/null) || die
service_start_after=not-running
if [[ "${service_active_after}" == active || "${service_active_after}" == reloading ]]; then
  service_start_after=$(systemctl show trading --property=ExecMainStartTimestamp --value 2>/dev/null) || die
fi
unit_stat_after=MISSING
unit_sha_after=MISSING
if [[ "${service_fragment_path}" == /etc/systemd/system/trading.service && -f "${service_fragment_path}" && ! -L "${service_fragment_path}" ]]; then
  unit_stat_after=$(stat --format='%Y:%s' "${service_fragment_path}" 2>/dev/null) || die
  unit_sha_after=$(sha256sum "${service_fragment_path}" 2>/dev/null | awk '{print $1}') || die
fi
[[ "${env_stat_before}" == "${env_stat_after}" && "${env_sha_before}" == "${env_sha_after}" ]] || die
[[ "${jar_stat_before}" == "${jar_stat_after}" && "${jar_sha}" == "${jar_sha_after}" ]] || die
[[ "${release_path}" == "${release_path_after}" ]] || die
[[ "${service_active}" == "${service_active_after}" && "${service_start_text}" == "${service_start_after}" ]] || die
[[ "${service_sub_state}" == "${service_sub_state_after}" ]] || die
[[ "${service_main_pid}" == "${service_main_pid_after}" ]] || die
[[ "${service_fragment_path}" == "${service_fragment_path_after}" ]] || die
[[ "${service_drop_in_paths}" == "${service_drop_in_paths_after}" ]] || die
[[ "${service_environment_files}" == "${service_environment_files_after}" ]] || die
[[ "${service_exec_start}" == "${service_exec_start_after}" ]] || die
[[ "${service_working_directory}" == "${service_working_directory_after}" ]] || die
[[ "${unit_stat_before}" == "${unit_stat_after}" ]] || die
if [[ "${unit_fragment_contract}" == EXPECTED ]]; then
  [[ "${unit_sha_after}" == "${EXPECTED_UNIT_SHA256}" ]] || die
fi
shopt -s nullglob
external_config_candidates_after=(
  /opt/trading/app/application*.properties
  /opt/trading/app/application*.yml
  /opt/trading/app/application*.yaml
  /opt/trading/app/config/application*.properties
  /opt/trading/app/config/application*.yml
  /opt/trading/app/config/application*.yaml
  /opt/trading/app/config/*/application*.properties
  /opt/trading/app/config/*/application*.yml
  /opt/trading/app/config/*/application*.yaml
)
shopt -u nullglob
[[ "${external_config_presence_before}" == "${#external_config_candidates_after[@]}" ]] || die

collection_phase_exit=${C0B_EXIT_PROTOCOL}
printf '%s\n' C0B_RAW_V1
emit java_code_sha service_active_state "${service_active}"
emit java_code_sha runtime_code_basis "${runtime_code_basis}"
emit java_code_sha runtime_release_sha "${release_sha}"
emit java_code_sha runtime_jar_sha256 "${jar_sha}"

emit effective_runtime_config spring_profile "${spring_profile}"
emit effective_runtime_config scheduling_enabled "${scheduling_enabled}"
emit effective_runtime_config execution_enabled "${execution_enabled}"
emit effective_runtime_config operation_mode "${operation_mode}"
server_binding=${server_address}
if [[ "${server_address}" != UNVERIFIED_NON_PROD_PROFILE ]]; then
  server_binding="${server_address}:${server_port}"
fi
emit effective_runtime_config server_binding "${server_binding}"
emit effective_runtime_config service_sub_state "${service_sub_state}"
emit effective_runtime_config environment_file_contract "${environment_file_contract}"
emit effective_runtime_config exec_start_contract "${exec_start_contract}"
emit effective_runtime_config unit_fragment_contract "${unit_fragment_contract}"
emit effective_runtime_config drop_in_contract "${drop_in_contract}"
emit effective_runtime_config working_directory_contract "${working_directory_contract}"
emit effective_runtime_config external_config_contract "${external_config_contract}"
emit effective_runtime_config file_and_unit_override_status "${effective_override_status}"
emit effective_runtime_config env_override_contract "${env_override_contract}"
emit effective_runtime_config decision_config_override_contract "${decision_config_override_contract}"
emit effective_runtime_config runtime_config_basis "${runtime_config_basis}"
emit effective_runtime_config live_process_environment_verification UNVERIFIED_BY_APPROVED_ROUTE

emit db_strategy_state latest_as_of_date "${latest_date}"
emit db_strategy_state signal_symbol "${latest_symbol}"
emit db_strategy_state strategy_on "${latest_on}"
emit db_strategy_state version "${latest_version}"
emit db_strategy_state weights "${latest_weights}"
emit db_strategy_state dd_bucket_parameter "${dd_bucket_parameter}"
emit db_strategy_state recovery_rule_parameter "${recovery_rule_parameter}"
emit db_strategy_state rebalance_tolerance_parameter "${rebalance_tolerance_parameter}"
emit db_strategy_state vix_threshold_parameter "${vix_threshold_parameter}"
emit db_strategy_state ma_200_guard_parameter "${ma_200_guard_parameter}"
emit db_strategy_state order_buffer_retry_policy_parameter "${order_buffer_retry_policy_parameter}"
emit db_strategy_state max_daily_turnover_parameter "${max_daily_turnover_parameter}"
emit db_strategy_state max_order_notional_parameter "${max_order_notional_parameter}"
emit db_strategy_state max_retry_exposure_parameter "${max_retry_exposure_parameter}"
emit db_strategy_state max_slippage_parameter "${max_slippage_parameter}"
emit db_strategy_state registry_semantics "database record not runtime effective"
emit db_strategy_state database_snapshot_consistency double-read-nonatomic

emit eod_state_pair latest_state "${latest_state}"
emit eod_state_pair latest_date_row_count "${latest_date_row_count}"
emit eod_state_pair previous_state "${previous_state}"
emit eod_state_pair previous_date_row_count "${previous_date_row_count}"

emit order_mode_ownership_quantity operating_mode_db "${db_mode}"
emit order_mode_ownership_quantity effective_mode "${effective_mode}"
emit order_mode_ownership_quantity effective_mode_source "${effective_mode_source}"
emit order_mode_ownership_quantity effective_mode_verification "${effective_mode_verification}"
emit order_mode_ownership_quantity execution_enabled "${execution_enabled}"
emit order_mode_ownership_quantity kill_switch_db "${kill_switch_db}"
emit order_mode_ownership_quantity submission_guard_derivation "${submission_guard_derivation}"
emit order_mode_ownership_quantity latest_job "${latest_job}"
emit order_mode_ownership_quantity open_order_count "${open_order_count}"
emit order_mode_ownership_quantity ownership_scope_verification "unverified outside Java host"
emit order_mode_ownership_quantity strategy_off_order_intent_policy "zero intents"
emit order_mode_ownership_quantity quantity_policy "absolute target notional gap divided by limit price and rounded down"
emit order_mode_ownership_quantity risk_limit_application "post-sizing order block"
emit order_mode_ownership_quantity buy_reference_price_policy "best ask then last"
emit order_mode_ownership_quantity sell_reference_price_policy "best bid then last"
emit order_mode_ownership_quantity order_price_rounding HALF_UP-2-decimals
emit order_mode_ownership_quantity quantity_rounding DOWN-integer
emit order_mode_ownership_quantity fee_pct 0.25
emit order_mode_ownership_quantity sell_proceeds_haircut 0.995
emit order_mode_ownership_quantity sell_quantity_cap "integer owned position"
emit order_mode_ownership_quantity buy_quantity_cap "remaining USD after fee"
emit order_mode_ownership_quantity cash_source_policy "KIS USD orderable cash plus fee-adjusted sell proceeds"
emit order_mode_ownership_quantity fx_quantity_policy "FX excluded from order quantity"
emit order_mode_ownership_quantity sell_priority TQQQ-QLD-QQQM
emit order_mode_ownership_quantity buy_priority QQQM-QLD-TQQQ
emit order_mode_ownership_quantity unsupported_holding_guard "QQQ only"
emit order_mode_ownership_quantity order_contract_source "pinned deployed Java revision"
emit order_mode_ownership_quantity order_contract_live_process_environment UNVERIFIED_BY_APPROVED_ROUTE

emit production_provider_contract quote_provider "KIS current-file configuration without a network call"
emit production_provider_contract auxiliary_provider "Yahoo configured without a network call"
emit production_provider_contract contract_source "pinned deployed source blobs"
emit production_provider_contract provider_network_call none
emit production_provider_contract provider_contract_verification UNVERIFIED_BY_APPROVED_ROUTE
emit production_provider_contract provider_live_process_environment UNVERIFIED_BY_APPROVED_ROUTE
emit production_provider_contract kis_origin_file_contract "${kis_origin_contract}"
emit production_provider_contract kis_quote_contract v1_해외주식-009
emit production_provider_contract kis_quote_method GET
emit production_provider_contract kis_quote_path /uapi/overseas-price/v1/quotations/price
emit production_provider_contract kis_auth_query "AUTH empty"
emit production_provider_contract kis_exchange_query "EXCD NAS"
emit production_provider_contract kis_symbol_query "SYMB runtime signal symbol"
emit production_provider_contract kis_session_scheme_contract "source scheme verified with credential value omitted"
emit production_provider_contract kis_default_header_contract "JSON content type and configured application credentials"
emit production_provider_contract kis_quote_transaction_prefix HHDFS
emit production_provider_contract kis_quote_transaction_digits_part_1 0000
emit production_provider_contract kis_quote_transaction_digits_part_2 0300
emit production_provider_contract kis_price_field output.base
emit production_provider_contract kis_status_validation "business result code not enforced before output.base use"
emit production_provider_contract kis_connect_timeout_binding "Netty connect timeout from KIS properties"
emit production_provider_contract kis_response_timeout_binding "Netty response and blocking timeout from KIS request property"
emit production_provider_contract yahoo_origin https://query1.finance.yahoo.com
emit production_provider_contract yahoo_chart_contract v8-chart
emit production_provider_contract yahoo_chart_method GET
emit production_provider_contract yahoo_chart_path '/v8/finance/chart/{symbol}'
emit production_provider_contract yahoo_vix_symbol '^VIX'
emit production_provider_contract yahoo_vix_field meta.regularMarketPrice
emit production_provider_contract yahoo_vix_null_policy "empty result on missing or failed response"
emit production_provider_contract yahoo_history_symbol "runtime signal symbol default QQQM"
emit production_provider_contract yahoo_history_range 1y
emit production_provider_contract yahoo_history_interval 1d
emit production_provider_contract yahoo_history_field indicators.quote.close
emit production_provider_contract yahoo_history_null_policy "null closes filtered"
emit production_provider_contract yahoo_history_selection "latest requested count"
emit production_provider_contract yahoo_ma_method "arithmetic mean scale 4 HALF_UP"
emit production_provider_contract yahoo_price_adjustment "unadjusted close field"
emit production_provider_contract yahoo_connect_timeout_binding "Netty connect timeout from Yahoo properties"
emit production_provider_contract yahoo_response_timeout_binding "Netty response and blocking timeout from Yahoo request property"
emit production_provider_contract yahoo_official_status UNVERIFIED_BY_APPROVED_ROUTE
emit production_provider_contract corporate_action_contract UNVERIFIED_BY_APPROVED_ROUTE

emit price_market_time_semantics strategy_price_kind previous-close
emit price_market_time_semantics market_zone America/New_York
emit price_market_time_semantics eod_schedule 16:15-business-days
emit price_market_time_semantics market_as_of_date_source scheduler-market-date
emit price_market_time_semantics upstream_observed_at NOT_PERSISTED
emit price_market_time_semantics upstream_available_at NOT_PERSISTED

emit kis_vix_ma200_semantics base_upstream_date NOT_PERSISTED
emit kis_vix_ma200_semantics base_date_verification UNVERIFIED_BY_APPROVED_ROUTE
emit kis_vix_ma200_semantics vix_source "Yahoo spot not queried"
emit kis_vix_ma200_semantics vix_observed_at NOT_PERSISTED
emit kis_vix_ma200_semantics ma_period "${ma_period}"
emit kis_vix_ma200_semantics ma_session_membership NOT_PERSISTED
emit kis_vix_ma200_semantics ma_price_adjustment UNVERIFIED_BY_APPROVED_ROUTE

emit freshness_retry_deadline kis_connect_timeout "${connect_timeout}"
emit freshness_retry_deadline kis_request_timeout "${request_timeout}"
emit freshness_retry_deadline yahoo_connect_timeout "${yahoo_connect_timeout}"
emit freshness_retry_deadline yahoo_request_timeout "${yahoo_request_timeout}"
emit freshness_retry_deadline order_max_attempts "${max_attempts}"
emit freshness_retry_deadline order_retry_wait_ms "${retry_wait_ms}"
emit freshness_retry_deadline input_freshness_enforcement NOT_IMPLEMENTED
emit freshness_retry_deadline eod_input_retry_schedule NOT_IMPLEMENTED
emit freshness_retry_deadline rebalance_input_retry_schedule NOT_IMPLEMENTED
emit freshness_retry_deadline eod_deadline NOT_IMPLEMENTED
emit freshness_retry_deadline rebalance_deadline NOT_IMPLEMENTED

emit operational_comparison_tolerances rebalance_tolerance_pct "${tolerance_pct}"
emit operational_comparison_tolerances tick_size "${tick_size}"
emit operational_comparison_tolerances price_rounding HALF_UP-2-decimals
emit operational_comparison_tolerances quantity_rounding DOWN-integer
emit operational_comparison_tolerances buy_priority QQQM-QLD-TQQQ
emit operational_comparison_tolerances sell_priority TQQQ-QLD-QQQM
emit operational_comparison_tolerances operational_comparison_window NOT_IMPLEMENTED
emit operational_comparison_tolerances operational_field_tolerances NOT_IMPLEMENTED
printf '%s\n' C0B_RAW_END
collection_phase_exit=0
