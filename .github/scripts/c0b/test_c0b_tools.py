from __future__ import annotations

import io
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from contract import (  # noqa: E402
    C0BError,
    ITEM_IDS,
    PINNED_DEPLOY_RUN_ID,
    PINNED_RUNTIME_JAR_SHA256,
    PINNED_RUNTIME_SHA,
    RAW_FACTS,
    _item5_live_environment_verified,
    _latest_job_is_completed,
    build_bundle_atomic,
    load_known_deployments,
    parse_raw_stream,
    resolve_deployment,
    safe_evidence_text,
    validate_bundle_directory,
    validate_capture_directory,
)
import transport_bundle as transport  # noqa: E402


FAKE_RUNTIME_SHA = PINNED_RUNTIME_SHA
FAKE_JAR_SHA = PINNED_RUNTIME_JAR_SHA256
CAPTURED_AT = "2026-08-09T13:00:00+09:00"
APPROVED_C0A_SHA = "a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c"
FIXTURE_SECRET_SENTINELS = (
    "fixture-db-secret",
    "fixture-kis-app-key-secret",
    "fixture-kis-app-secret",
    "fixture-kis-account-secret",
    "fixture-kis-cano-secret",
    "fixture-kis-product-secret",
    "fixture-api-secret",
    "fixture-webhook-secret",
    "fixture-backup-user-secret",
    "fixture-backup-host-secret",
    "fixture-backup-key-secret",
)
APPROVED_COLLECTOR_SQL = {
    "SQL_LATEST_STATE": (
        "SELECT DATE_FORMAT(as_of_date,'%Y-%m-%d'), signal_symbol, "
        "CAST(strategy_on AS UNSIGNED), version, "
        "CONCAT('QQQM=',CAST(w_base AS CHAR),',QLD=',CAST(w_qld AS CHAR),"
        "',TQQQ=',CAST(w_tqqq AS CHAR)) FROM strategy_state "
        "ORDER BY as_of_date DESC, id DESC LIMIT 1"
    ),
    "SQL_EOD_PAIR": (
        "SELECT CONCAT(DATE_FORMAT(s.as_of_date,'%Y-%m-%d'),',',"
        "s.signal_symbol,',',CAST(s.ath AS CHAR),',',CAST(s.last_close AS CHAR),"
        "',',CAST(s.drawdown_pct AS CHAR),',',"
        "CAST(s.max_drawdown_pct_since_ath AS CHAR),',',s.phase,',',"
        "s.dd_bucket,',',CAST(s.strategy_on AS UNSIGNED),',',s.version,',',"
        "CAST(s.w_base AS CHAR),',',CAST(s.w_qld AS CHAR),',',"
        "CAST(s.w_tqqq AS CHAR)),CAST(d.row_count AS UNSIGNED) "
        "FROM strategy_state s JOIN (SELECT as_of_date,MAX(id) AS id,"
        "COUNT(*) AS row_count FROM strategy_state GROUP BY as_of_date "
        "ORDER BY as_of_date DESC LIMIT 2) d ON d.id=s.id "
        "ORDER BY s.as_of_date DESC,s.id DESC"
    ),
    "SQL_OPERATING_MODE": (
        "SELECT COALESCE((SELECT control_value FROM trading_control "
        "WHERE control_key='OPERATING_MODE' LIMIT 1),'NOT_PERSISTED')"
    ),
    "SQL_KILL_SWITCH": (
        "SELECT COALESCE((SELECT UPPER(control_value) FROM trading_control "
        "WHERE control_key='KILL_SWITCH' LIMIT 1),'NOT_PERSISTED')"
    ),
    "SQL_LATEST_JOB": (
        "SELECT COALESCE((SELECT CONCAT(DATE_FORMAT(signal_date,'%Y-%m-%d'),"
        "',',status) FROM execution_job ORDER BY signal_date DESC, "
        "execute_after DESC, id DESC LIMIT 1),'none')"
    ),
    "SQL_OPEN_ORDER_COUNT": (
        "SELECT COUNT(*) FROM execution_order WHERE status IN "
        "('PLANNED','REQUESTED','ACCEPTED','CONFIRMATION_REQUIRED')"
    ),
}


def fake_raw_values(runtime_sha: str = FAKE_RUNTIME_SHA) -> dict[str, dict[str, str]]:
    return {
        "java_code_sha": {
            "service_active_state": "inactive",
            "runtime_code_basis": "service not running",
            "runtime_release_sha": runtime_sha,
            "runtime_jar_sha256": FAKE_JAR_SHA,
        },
        "effective_runtime_config": {
            "spring_profile": "prod",
            "scheduling_enabled": "true",
            "execution_enabled": "false",
            "operation_mode": "PAPER",
            "server_binding": "127.0.0.1:8080",
            "service_sub_state": "dead",
            "environment_file_contract": "EXPECTED",
            "exec_start_contract": "EXPECTED",
            "unit_fragment_contract": "EXPECTED",
            "drop_in_contract": "EXPECTED",
            "working_directory_contract": "EXPECTED",
            "external_config_contract": "EXPECTED",
            "file_and_unit_override_status": "NO_UNVERIFIED_OVERRIDE",
            "env_override_contract": "EXPECTED",
            "decision_config_override_contract": "EXPECTED",
            "runtime_config_basis": "next start configuration only",
            "live_process_environment_verification": "UNVERIFIED_BY_APPROVED_ROUTE",
        },
        "db_strategy_state": {
            "latest_as_of_date": "2026-08-08",
            "signal_symbol": "QQQM",
            "strategy_on": "1",
            "version": "2",
            "weights": "QQQM=100.0000,QLD=0.0000,TQQQ=0.0000",
            "dd_bucket_parameter": "PROVISIONAL:15 / 25 / 35 / 45%",
            "recovery_rule_parameter": "PROVISIONAL:최대 DD 15% 이상 + 현재 DD 10% 이하",
            "rebalance_tolerance_parameter": "PROVISIONAL:5.0%",
            "vix_threshold_parameter": "PROVISIONAL:35",
            "ma_200_guard_parameter": "PROVISIONAL:QQQM < MA200 시 공격 버킷 1단계 축소",
            "order_buffer_retry_policy_parameter": "PROVISIONAL:초기 BUY 0 tick / SELL 0 tick, 재시도 BUY +1 tick / SELL +1 tick, 최대 3회, 대기 2000ms",
            "max_daily_turnover_parameter": "PROVISIONAL:미정의 (0으로 비활성)",
            "max_order_notional_parameter": "PROVISIONAL:미정의 (0으로 비활성)",
            "max_retry_exposure_parameter": "PROVISIONAL:미정의 (0으로 비활성)",
            "max_slippage_parameter": "PROVISIONAL:미정의 (0으로 비활성)",
            "registry_semantics": "database record not runtime effective",
            "database_snapshot_consistency": "double-read-nonatomic",
        },
        "eod_state_pair": {
            "latest_state": "2026-08-08,QQQM,500.0000,480.0000,4.0000,12.0000,NORMAL,LESS_THAN_15,1,2,100.0000,0.0000,0.0000",
            "latest_date_row_count": "1",
            "previous_state": "2026-08-07,QQQM,500.0000,475.0000,5.0000,12.0000,NORMAL,LESS_THAN_15,1,2,100.0000,0.0000,0.0000",
            "previous_date_row_count": "1",
        },
        "order_mode_ownership_quantity": {
            "operating_mode_db": "PAPER",
            "effective_mode": "PAPER",
            "effective_mode_source": "database record",
            "effective_mode_verification": "VERIFIED_DATABASE_RECORD",
            "execution_enabled": "false",
            "kill_switch_db": "OFF",
            "submission_guard_derivation": "BLOCKED_PAPER_MODE",
            "latest_job": "2026-08-08,COMPLETED",
            "open_order_count": "0",
            "ownership_scope_verification": "unverified outside Java host",
            "strategy_off_order_intent_policy": "zero intents",
            "quantity_policy": "absolute target notional gap divided by limit price and rounded down",
            "risk_limit_application": "post-sizing order block",
            "buy_reference_price_policy": "best ask then last",
            "sell_reference_price_policy": "best bid then last",
            "order_price_rounding": "HALF_UP-2-decimals",
            "quantity_rounding": "DOWN-integer",
            "fee_pct": "0.25",
            "sell_proceeds_haircut": "0.995",
            "sell_quantity_cap": "integer owned position",
            "buy_quantity_cap": "remaining USD after fee",
            "cash_source_policy": "KIS USD orderable cash plus fee-adjusted sell proceeds",
            "fx_quantity_policy": "FX excluded from order quantity",
            "sell_priority": "TQQQ-QLD-QQQM",
            "buy_priority": "QQQM-QLD-TQQQ",
            "unsupported_holding_guard": "QQQ only",
            "order_contract_source": "pinned deployed Java revision",
            "order_contract_live_process_environment": "UNVERIFIED_BY_APPROVED_ROUTE",
        },
        "production_provider_contract": {
            "quote_provider": "KIS current-file configuration without a network call",
            "auxiliary_provider": "Yahoo configured without a network call",
            "contract_source": "pinned deployed source blobs",
            "provider_network_call": "none",
            "provider_contract_verification": "UNVERIFIED_BY_APPROVED_ROUTE",
            "provider_live_process_environment": "UNVERIFIED_BY_APPROVED_ROUTE",
            "kis_origin_file_contract": "EXPECTED_PRODUCTION",
            "kis_quote_contract": "v1_해외주식-009",
            "kis_quote_method": "GET",
            "kis_quote_path": "/uapi/overseas-price/v1/quotations/price",
            "kis_auth_query": "AUTH empty",
            "kis_exchange_query": "EXCD NAS",
            "kis_symbol_query": "SYMB runtime signal symbol",
            "kis_session_scheme_contract": "source scheme verified with credential value omitted",
            "kis_default_header_contract": "JSON content type and configured application credentials",
            "kis_quote_transaction_prefix": "HHDFS",
            "kis_quote_transaction_digits_part_1": "0000",
            "kis_quote_transaction_digits_part_2": "0300",
            "kis_price_field": "output.base",
            "kis_status_validation": "business result code not enforced before output.base use",
            "kis_connect_timeout_binding": "Netty connect timeout from KIS properties",
            "kis_response_timeout_binding": "Netty response and blocking timeout from KIS request property",
            "yahoo_origin": "https://query1.finance.yahoo.com",
            "yahoo_chart_contract": "v8-chart",
            "yahoo_chart_method": "GET",
            "yahoo_chart_path": "/v8/finance/chart/{symbol}",
            "yahoo_vix_symbol": "^VIX",
            "yahoo_vix_field": "meta.regularMarketPrice",
            "yahoo_vix_null_policy": "empty result on missing or failed response",
            "yahoo_history_symbol": "runtime signal symbol default QQQM",
            "yahoo_history_range": "1y",
            "yahoo_history_interval": "1d",
            "yahoo_history_field": "indicators.quote.close",
            "yahoo_history_null_policy": "null closes filtered",
            "yahoo_history_selection": "latest requested count",
            "yahoo_ma_method": "arithmetic mean scale 4 HALF_UP",
            "yahoo_price_adjustment": "unadjusted close field",
            "yahoo_connect_timeout_binding": "Netty connect timeout from Yahoo properties",
            "yahoo_response_timeout_binding": "Netty response and blocking timeout from Yahoo request property",
            "yahoo_official_status": "UNVERIFIED_BY_APPROVED_ROUTE",
            "corporate_action_contract": "UNVERIFIED_BY_APPROVED_ROUTE",
        },
        "price_market_time_semantics": {
            "strategy_price_kind": "previous-close",
            "market_zone": "America/New_York",
            "eod_schedule": "16:15-business-days",
            "market_as_of_date_source": "scheduler-market-date",
            "upstream_observed_at": "NOT_PERSISTED",
            "upstream_available_at": "NOT_PERSISTED",
        },
        "kis_vix_ma200_semantics": {
            "base_upstream_date": "NOT_PERSISTED",
            "base_date_verification": "UNVERIFIED_BY_APPROVED_ROUTE",
            "vix_source": "Yahoo spot not queried",
            "vix_observed_at": "NOT_PERSISTED",
            "ma_period": "200",
            "ma_session_membership": "NOT_PERSISTED",
            "ma_price_adjustment": "UNVERIFIED_BY_APPROVED_ROUTE",
        },
        "freshness_retry_deadline": {
            "kis_connect_timeout": "3s",
            "kis_request_timeout": "10s",
            "yahoo_connect_timeout": "3s",
            "yahoo_request_timeout": "5s",
            "order_max_attempts": "3",
            "order_retry_wait_ms": "2000",
            "input_freshness_enforcement": "NOT_IMPLEMENTED",
            "eod_input_retry_schedule": "NOT_IMPLEMENTED",
            "rebalance_input_retry_schedule": "NOT_IMPLEMENTED",
            "eod_deadline": "NOT_IMPLEMENTED",
            "rebalance_deadline": "NOT_IMPLEMENTED",
        },
        "operational_comparison_tolerances": {
            "rebalance_tolerance_pct": "5.0",
            "tick_size": "0.01",
            "price_rounding": "HALF_UP-2-decimals",
            "quantity_rounding": "DOWN-integer",
            "buy_priority": "QQQM-QLD-TQQQ",
            "sell_priority": "TQQQ-QLD-QQQM",
            "operational_comparison_window": "NOT_IMPLEMENTED",
            "operational_field_tolerances": "NOT_IMPLEMENTED",
        },
    }


def raw_protocol(
    values: dict[str, dict[str, str]] | None = None,
    runtime_sha: str = FAKE_RUNTIME_SHA,
) -> bytes:
    values = fake_raw_values(runtime_sha) if values is None else values
    lines = ["C0B_RAW_V1"]
    for item_id in ITEM_IDS[:10]:
        for name in RAW_FACTS[item_id]:
            lines.append(f"{item_id}\t{name}\t{values[item_id][name]}")
    lines.append("C0B_RAW_END")
    return ("\n".join(lines) + "\n").encode()


def decision_record(revision: str = APPROVED_C0A_SHA) -> str:
    rows = []
    for number in range(1, 11):
        rows.append(
            f"| D-{number:02d} | fixture | 조건부 승인 | Inys | "
            f"2026-08-09T12:00:00+09:00 | 문서={revision}; 코드={revision} |"
        )
    scope = (
        "대상=12개; 접근방법=GitHub Actions PROD SSH로 운영 호스트 shell 조회·DB SELECT; "
        "권한=읽기 전용; 정제=필수; 저장위치=docs/v2-cutover/evidence/c0b/; "
        "원문저장=금지; 보존=Git 이력"
    )
    rows.append(
        f"| C0-B 읽기 전용 수집 승인 | {scope} | - | 수집 승인 | Inys | "
        "2026-08-09T12:30:00+09:00 |"
    )
    return "\n".join(rows) + "\n"


class C0BToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.runner_temp = self.root / "runner-temp"
        self.runner_temp.mkdir()
        self.fake_repository = self.root / "fake-repository"
        self.fake_repository.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=self.fake_repository, check=True)
        subprocess.run(
            ["git", "config", "user.name", "Fixture"],
            cwd=self.fake_repository,
            check=True,
        )
        subprocess.run(
            ["git", "config", "user.email", "fixture@example.invalid"],
            cwd=self.fake_repository,
            check=True,
        )
        commit_env = dict(os.environ)
        commit_env["GIT_AUTHOR_DATE"] = "2026-08-09T00:00:00+09:00"
        commit_env["GIT_COMMITTER_DATE"] = "2026-08-09T00:00:00+09:00"
        subprocess.run(
            ["git", "commit", "-q", "--allow-empty", "-m", "fixture"],
            cwd=self.fake_repository,
            env=commit_env,
            check=True,
        )
        self.workflow_sha = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.fake_repository, text=True
        ).strip()
        subprocess.run(
            [
                "git",
                "fetch",
                "-q",
                str(SCRIPT_DIR.parents[2]),
                "HEAD",
            ],
            cwd=self.fake_repository,
            check=True,
        )
        self.runtime_sha = PINNED_RUNTIME_SHA
        self.workflow_context = {
            "expected_workflow_sha": self.workflow_sha,
            "expected_workflow_run_id": "12345678901",
            "expected_workflow_run_attempt": "1",
        }
        self.decision_file = self.root / "C0_DECISIONS.md"
        self.decision_file.write_text(decision_record(), encoding="utf-8")
        self.known_file = self.root / "known.json"
        self.known_file.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "kind": "c0b_known_deployments",
                    "deployments": [
                        {
                            "git_sha": self.runtime_sha,
                            "jar_sha256": FAKE_JAR_SHA,
                            "run_id": int(PINNED_DEPLOY_RUN_ID),
                            "run_head_sha": self.runtime_sha,
                            "run_conclusion": "success",
                            "workflow_file": ".github/workflows/deploy-prod.yml",
                            "run_url": (
                                "https://github.com/ksi2564/systematic-trading/actions/runs/"
                                + PINNED_DEPLOY_RUN_ID
                            ),
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def sanitize(
        self, payload: bytes | None = None, output_name: str = "sanitized"
    ) -> subprocess.CompletedProcess[bytes]:
        output_dir = self.runner_temp / output_name
        env = dict(os.environ)
        env["RUNNER_TEMP"] = str(self.runner_temp)
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT_DIR / "sanitize_capture.py"),
                "--output-dir",
                str(output_dir),
                "--captured-at",
                CAPTURED_AT,
                "--collector",
                "github-actions-prod-read-only",
                "--workflow-sha",
                self.workflow_sha,
                "--workflow-run-id",
                "12345678901",
                "--workflow-run-attempt",
                "1",
                "--repository-root",
                str(self.fake_repository),
                "--decision-file",
                str(self.decision_file),
                "--known-deployments",
                str(self.known_file),
            ],
            input=(
                raw_protocol(runtime_sha=self.runtime_sha)
                if payload is None
                else payload
            ),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            check=False,
        )

    def _write_fixture_command(self, path: Path, source: str) -> None:
        path.write_text(textwrap.dedent(source).lstrip(), encoding="utf-8")
        path.chmod(0o755)

    def _collector_fixture_sql(self) -> tuple[dict[str, tuple[str, str]], list[str]]:
        source = (SCRIPT_DIR / "collect_remote.sh").read_text(encoding="utf-8")

        def constant(name: str) -> str:
            match = re.search(rf'^readonly {name}="([^"]+)"$', source, re.MULTILINE)
            self.assertIsNotNone(match, name)
            return match.group(1)

        for name, approved_sql in APPROVED_COLLECTOR_SQL.items():
            self.assertEqual(constant(name), approved_sql)

        expected = fake_raw_values(self.runtime_sha)
        registry_names = {
            "DD_BUCKET": "dd_bucket_parameter",
            "RECOVERY_RULE": "recovery_rule_parameter",
            "REBALANCE_TOLERANCE": "rebalance_tolerance_parameter",
            "VIX_THRESHOLD": "vix_threshold_parameter",
            "MA_200_GUARD": "ma_200_guard_parameter",
            "ORDER_BUFFER_RETRY_POLICY": "order_buffer_retry_policy_parameter",
            "MAX_DAILY_TURNOVER_PCT": "max_daily_turnover_parameter",
            "MAX_ORDER_NOTIONAL_USD": "max_order_notional_parameter",
            "MAX_RETRY_EXPOSURE_USD": "max_retry_exposure_parameter",
            "MAX_SLIPPAGE_PCT": "max_slippage_parameter",
        }
        registry_ids = [f"registry_{key}" for key in registry_names]
        responses: dict[str, tuple[str, str]] = {
            APPROVED_COLLECTOR_SQL["SQL_LATEST_STATE"]: (
                "latest_state",
                "\t".join(
                    (
                        expected["db_strategy_state"]["latest_as_of_date"],
                        expected["db_strategy_state"]["signal_symbol"],
                        expected["db_strategy_state"]["strategy_on"],
                        expected["db_strategy_state"]["version"],
                        expected["db_strategy_state"]["weights"],
                    )
                ),
            ),
            APPROVED_COLLECTOR_SQL["SQL_EOD_PAIR"]: (
                "eod_pair",
                "\n".join(
                    (
                        expected["eod_state_pair"]["latest_state"]
                        + "\t"
                        + expected["eod_state_pair"]["latest_date_row_count"],
                        expected["eod_state_pair"]["previous_state"]
                        + "\t"
                        + expected["eod_state_pair"]["previous_date_row_count"],
                    )
                ),
            ),
            APPROVED_COLLECTOR_SQL["SQL_OPERATING_MODE"]: ("operating_mode", "PAPER"),
            APPROVED_COLLECTOR_SQL["SQL_KILL_SWITCH"]: ("kill_switch", "OFF"),
            APPROVED_COLLECTOR_SQL["SQL_LATEST_JOB"]: (
                "latest_job",
                expected["order_mode_ownership_quantity"]["latest_job"],
            ),
            APPROVED_COLLECTOR_SQL["SQL_OPEN_ORDER_COUNT"]: ("open_count", "0"),
        }
        for key, fact_name in registry_names.items():
            sql = (
                "SELECT COALESCE((SELECT CONCAT(status,':',effective_value) "
                "FROM parameter_registry_record WHERE registry_key='"
                + key
                + "' LIMIT 1),'MISSING')"
            )
            responses[sql] = (
                f"registry_{key}",
                expected["db_strategy_state"][fact_name],
            )
        expected_order = (
            ["latest_state"]
            + registry_ids
            + ["eod_pair", "operating_mode", "kill_switch", "latest_job", "open_count"]
            + ["latest_state", "eod_pair"]
            + registry_ids
            + ["operating_mode", "kill_switch", "latest_job", "open_count"]
        )
        return responses, expected_order

    def _run_collector_fixture(
        self, failure: str = "", *, active: bool = False
    ) -> tuple[subprocess.CompletedProcess[bytes], Path, list[str]]:
        fixture_name = failure or ("active-success" if active else "success")
        fixture = self.root / ("collector-" + fixture_name)
        bin_dir = fixture / "bin"
        app_dir = fixture / "opt/trading/app"
        dashboard_releases = fixture / "opt/trading/admin-dashboard/releases"
        env_file = fixture / "etc/trading/trading.env"
        unit_file = fixture / "etc/systemd/system/trading.service"
        for directory in (bin_dir, app_dir, dashboard_releases, env_file.parent, unit_file.parent):
            directory.mkdir(parents=True, exist_ok=True)

        repository_root = SCRIPT_DIR.parents[2]
        env_text = subprocess.check_output(
            [
                "git",
                "--no-replace-objects",
                "show",
                f"{PINNED_RUNTIME_SHA}:deploy/env/trading.env.example",
            ],
            cwd=repository_root,
            text=True,
        )
        secret_values = {
            "SPRING_DATASOURCE_PASSWORD": "fixture-db-secret",
            "KIS_APP_KEY": "fixture-kis-app-key-secret",
            "KIS_APP_SECRET": "fixture-kis-app-secret",
            "KIS_ACCOUNT_NO": "fixture-kis-account-secret",
            "KIS_CANO": "fixture-kis-cano-secret",
            "KIS_ACNT_PRDT_CD": "fixture-kis-product-secret",
            "TRADING_API_KEY": "fixture-api-secret",
        }
        for key, sentinel in secret_values.items():
            env_text = env_text.replace(
                f"{key}='change-me'", f"{key}='{sentinel}'"
            )
        env_text = env_text.replace(
            "TRADING_DISCORD_WEBHOOK_URL=''",
            "TRADING_DISCORD_WEBHOOK_URL='https://fixture.invalid/fixture-webhook-secret'",
        )
        env_text = env_text.replace(
            "MAC_BACKUP_SSH_USER='backup'",
            "MAC_BACKUP_SSH_USER='fixture-backup-user-secret'",
        )
        env_text = env_text.replace(
            "MAC_BACKUP_SSH_HOST='mac-mini.tailnet.ts.net'",
            "MAC_BACKUP_SSH_HOST='fixture-backup-host-secret.invalid'",
        )
        env_text = env_text.replace(
            "MAC_BACKUP_SSH_KEY='/home/ubuntu/.ssh/trading-backup'",
            "MAC_BACKUP_SSH_KEY='/fixture-backup-key-secret'",
        )
        if failure == "env-shape":
            env_text += "SPRING_PROFILES_ACTIVE=duplicate\n"
        elif failure == "env-allowlist":
            env_text += "UNKNOWN_KEY='rejected'\n"
        elif failure == "db-env":
            env_text = re.sub(
                r"^SPRING_DATASOURCE_USERNAME=.*\n",
                "",
                env_text,
                flags=re.MULTILINE,
            )
        elif failure == "db-url":
            env_text = re.sub(
                r"^SPRING_DATASOURCE_URL=.*$",
                "SPRING_DATASOURCE_URL='jdbc:mysql://db.invalid:3306/trading'",
                env_text,
                flags=re.MULTILINE,
            )
        elif failure == "config":
            env_text += "SERVER_ADDRESS='0.0.0.0'\n"
        env_file.write_text(env_text, encoding="utf-8")
        if failure == "env-file":
            env_target = env_file.with_name("trading.env.target")
            env_file.replace(env_target)
            env_file.symlink_to(env_target)
        unit_file.write_bytes(
            subprocess.check_output(
                [
                    "git",
                    "--no-replace-objects",
                    "show",
                    f"{PINNED_RUNTIME_SHA}:deploy/systemd/trading.service",
                ],
                cwd=repository_root,
            )
        )
        jar_file = app_dir / "trading.jar"
        jar_file.write_bytes(b"synthetic collector fixture jar\n")
        if failure == "jar-file":
            jar_target = jar_file.with_name("trading.jar.target")
            jar_file.replace(jar_target)
            jar_file.symlink_to(jar_target)
        release_dir = dashboard_releases / PINNED_RUNTIME_SHA
        release_dir.mkdir()
        dashboard_current = dashboard_releases.parent / "current"
        dashboard_current.symlink_to(release_dir)

        mysql_log = fixture / "mysql.calls"
        systemctl_log = fixture / "systemctl.calls"
        sha256sum_log = fixture / "sha256sum.calls"
        stat_log = fixture / "stat.calls"
        readlink_log = fixture / "readlink.calls"
        date_log = fixture / "date.calls"
        for log_path in (
            mysql_log,
            systemctl_log,
            sha256sum_log,
            stat_log,
            readlink_log,
            date_log,
        ):
            log_path.write_text("", encoding="utf-8")

        immutable_files = {
            env_file: env_file.read_bytes(),
            unit_file: unit_file.read_bytes(),
            jar_file: jar_file.read_bytes(),
        }
        dashboard_target = os.readlink(dashboard_current)

        support_commands = {
            "bash": shutil.which("bash"),
            "python3": sys.executable,
            "tr": shutil.which("tr"),
            "sed": shutil.which("sed"),
        }
        for command_name, command_path in support_commands.items():
            self.assertIsNotNone(command_path)
            (bin_dir / command_name).symlink_to(Path(command_path).resolve())
        real_awk = shutil.which("awk")
        self.assertIsNotNone(real_awk)
        self._write_fixture_command(
            bin_dir / "awk",
            r'''
            #!/usr/bin/env python3
            import os
            import sys

            if (
                os.environ.get("C0B_FIXTURE_FAILURE") == "override-scan"
                and len(sys.argv) > 1
                and sys.argv[1] == "-F="
            ):
                raise SystemExit(70)
            real_awk = os.environ["C0B_FIXTURE_REAL_AWK"]
            os.execv(real_awk, [real_awk, *sys.argv[1:]])
            ''',
        )

        self._write_fixture_command(
            bin_dir / "mysql",
            r'''
            #!/usr/bin/env python3
            import json
            import os
            import sys
            from pathlib import Path

            expected_prefix = [
                "--no-defaults",
                "--batch",
                "--skip-column-names",
                "--raw",
                "--init-command=SET SESSION TRANSACTION READ ONLY",
                "--connect-timeout=5",
                "--host=127.0.0.1",
                "--port=3306",
                "--user=trading",
                "--database=trading",
            ]
            if os.environ.get("MYSQL_PWD") != "fixture-db-secret":
                raise SystemExit(70)
            if sys.argv[1:-1] != expected_prefix or not sys.argv[-1].startswith("--execute="):
                raise SystemExit(70)
            sql = sys.argv[-1][len("--execute="):]
            responses = json.loads(os.environ["C0B_FIXTURE_SQL_RESPONSES"])
            if sql not in responses:
                raise SystemExit(70)
            query_id, response = responses[sql]
            log_path = Path(os.environ["C0B_FIXTURE_MYSQL_LOG"])
            prior = log_path.read_text(encoding="utf-8").splitlines()
            with log_path.open("a", encoding="utf-8") as handle:
                handle.write(query_id + "\n")
            failure = os.environ.get("C0B_FIXTURE_FAILURE", "")
            if failure == "database" and not prior:
                raise SystemExit(70)
            if (
                failure == "consistency"
                and query_id == "latest_state"
                and prior.count(query_id) == 1
            ):
                response = response.replace("\t2\t", "\t3\t", 1)
            if failure == "protocol" and query_id == "registry_MAX_SLIPPAGE_PCT":
                response = "PROVISIONAL:" + ("x" * 501)
            sys.stdout.buffer.write(response.encode("utf-8") + b"\n")
            ''',
        )
        self._write_fixture_command(
            bin_dir / "systemctl",
            r'''
            #!/usr/bin/env python3
            import os
            import sys
            from pathlib import Path

            if (
                len(sys.argv) != 5
                or sys.argv[1:3] != ["show", "trading"]
                or sys.argv[4] != "--value"
            ):
                raise SystemExit(70)
            if not sys.argv[3].startswith("--property="):
                raise SystemExit(70)
            prop = sys.argv[3][len("--property="):]
            log_path = Path(os.environ["C0B_FIXTURE_SYSTEMCTL_LOG"])
            prior = log_path.read_text(encoding="utf-8").splitlines()
            with log_path.open("a", encoding="utf-8") as handle:
                handle.write(prop + "\n")
            failure = os.environ.get("C0B_FIXTURE_FAILURE", "")
            active = os.environ.get("C0B_FIXTURE_ACTIVE") == "1"
            if failure == "runtime" and not prior:
                raise SystemExit(70)
            values = {
                "LoadState": "loaded",
                "ActiveState": "active" if active else "inactive",
                "SubState": "running" if active else "dead",
                "MainPID": "1234" if active else "0",
                "FragmentPath": os.environ["C0B_FIXTURE_UNIT_FILE"],
                "DropInPaths": "",
                "EnvironmentFiles": os.environ["C0B_FIXTURE_ENV_FILE"],
                "ExecStart": (
                    "/usr/bin/java -Xms256m -Xmx1024m -jar "
                    + os.environ["C0B_FIXTURE_JAR_FILE"]
                ),
                "WorkingDirectory": os.environ["C0B_FIXTURE_APP_DIR"],
                "ExecMainStartTimestamp": os.environ["C0B_FIXTURE_START_TEXT"],
            }
            if prop not in values:
                raise SystemExit(70)
            value = values[prop]
            if failure == "host-stability" and prop == "MainPID" and prior.count(prop) == 1:
                value = "1"
            sys.stdout.buffer.write(value.encode("utf-8") + b"\n")
            ''',
        )
        self._write_fixture_command(
            bin_dir / "sha256sum",
            r'''
            #!/usr/bin/env python3
            import hashlib
            import os
            import sys
            from pathlib import Path

            if len(sys.argv) != 2:
                raise SystemExit(70)
            path = Path(sys.argv[1])
            allowed = {
                Path(os.environ["C0B_FIXTURE_ENV_FILE"]),
                Path(os.environ["C0B_FIXTURE_JAR_FILE"]),
                Path(os.environ["C0B_FIXTURE_UNIT_FILE"]),
            }
            if path not in allowed:
                raise SystemExit(70)
            with Path(os.environ["C0B_FIXTURE_SHA256SUM_LOG"]).open(
                "a", encoding="utf-8"
            ) as handle:
                handle.write(str(path) + "\n")
            if path == Path(os.environ["C0B_FIXTURE_JAR_FILE"]):
                digest = os.environ["C0B_FIXTURE_JAR_SHA256"]
            elif path == Path(os.environ["C0B_FIXTURE_UNIT_FILE"]):
                digest = os.environ["C0B_FIXTURE_UNIT_SHA256"]
            else:
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
            sys.stdout.write(f"{digest}  {path}\n")
            ''',
        )
        self._write_fixture_command(
            bin_dir / "stat",
            r'''
            #!/usr/bin/env python3
            import os
            import sys
            from pathlib import Path

            if len(sys.argv) != 3 or not sys.argv[1].startswith("--format="):
                raise SystemExit(70)
            format_value = sys.argv[1][len("--format="):]
            path = Path(sys.argv[2])
            allowed = {
                Path(os.environ["C0B_FIXTURE_ENV_FILE"]),
                Path(os.environ["C0B_FIXTURE_JAR_FILE"]),
                Path(os.environ["C0B_FIXTURE_UNIT_FILE"]),
            }
            if path not in allowed:
                raise SystemExit(70)
            if os.environ.get("C0B_FIXTURE_FAILURE") == "fingerprint":
                raise SystemExit(70)
            with Path(os.environ["C0B_FIXTURE_STAT_LOG"]).open(
                "a", encoding="utf-8"
            ) as handle:
                handle.write(format_value + "\t" + str(path) + "\n")
            if format_value == "%Y:%s":
                sys.stdout.write(f"1000:{path.stat().st_size}\n")
            elif format_value == "%Y":
                sys.stdout.write("1000\n")
            else:
                raise SystemExit(70)
            ''',
        )
        self._write_fixture_command(
            bin_dir / "readlink",
            r'''
            #!/usr/bin/env python3
            import os
            import sys
            from pathlib import Path

            if len(sys.argv) != 3 or sys.argv[1] != "-f":
                raise SystemExit(70)
            if sys.argv[2] != os.environ["C0B_FIXTURE_DASHBOARD_CURRENT"]:
                raise SystemExit(70)
            with Path(os.environ["C0B_FIXTURE_READLINK_LOG"]).open(
                "a", encoding="utf-8"
            ) as handle:
                handle.write(sys.argv[2] + "\n")
            sys.stdout.write(os.environ["C0B_FIXTURE_DASHBOARD_RELEASE"] + "\n")
            ''',
        )
        self._write_fixture_command(
            bin_dir / "date",
            r'''
            #!/usr/bin/env python3
            import os
            import sys
            from pathlib import Path

            expected = [
                "--date=" + os.environ["C0B_FIXTURE_START_TEXT"],
                "+%s",
            ]
            if sys.argv[1:] != expected:
                raise SystemExit(70)
            with Path(os.environ["C0B_FIXTURE_DATE_LOG"]).open(
                "a", encoding="utf-8"
            ) as handle:
                handle.write("\t".join(sys.argv[1:]) + "\n")
            sys.stdout.write("2000\n")
            ''',
        )

        missing_command_by_failure = {
            "mysql-client": "mysql",
            "systemctl": "systemctl",
            "inspection-tools": "tr",
        }
        missing_command = missing_command_by_failure.get(failure)
        if missing_command is not None:
            (bin_dir / missing_command).unlink()

        collector_source = (SCRIPT_DIR / "collect_remote.sh").read_text(encoding="utf-8")
        path_replacements = (
            ("/opt/trading/admin-dashboard/current", str(dashboard_current)),
            ("/etc/systemd/system/trading.service", str(unit_file)),
            ("/etc/trading/trading.env", str(env_file)),
            ("/opt/trading/app", str(app_dir)),
        )
        for original, replacement in path_replacements:
            self.assertIn(original, collector_source)
            self.assertRegex(replacement, r"^/[A-Za-z0-9._/-]+$")
            collector_source = collector_source.replace(original, replacement)
        root_guard = '[[ "${EUID}" == 0 ]] || die'
        self.assertEqual(collector_source.count(root_guard), 1)
        collector_source = collector_source.replace(root_guard, ":")
        fixture_collector = fixture / "collect_remote.sh"
        fixture_collector.write_text(collector_source, encoding="utf-8")
        fixture_collector.chmod(0o755)

        sql_responses, expected_order = self._collector_fixture_sql()
        command_env = {
                "PATH": str(bin_dir),
                "BASH_ENV": "/dev/null",
                "PYTHONDONTWRITEBYTECODE": "1",
                "C0B_FIXTURE_FAILURE": failure,
                "C0B_FIXTURE_REAL_AWK": str(real_awk),
                "C0B_FIXTURE_ACTIVE": "1" if active else "0",
                "C0B_FIXTURE_SQL_RESPONSES": json.dumps(sql_responses),
                "C0B_FIXTURE_MYSQL_LOG": str(mysql_log),
                "C0B_FIXTURE_SYSTEMCTL_LOG": str(systemctl_log),
                "C0B_FIXTURE_SHA256SUM_LOG": str(sha256sum_log),
                "C0B_FIXTURE_STAT_LOG": str(stat_log),
                "C0B_FIXTURE_READLINK_LOG": str(readlink_log),
                "C0B_FIXTURE_DATE_LOG": str(date_log),
                "C0B_FIXTURE_ENV_FILE": str(env_file),
                "C0B_FIXTURE_UNIT_FILE": str(unit_file),
                "C0B_FIXTURE_JAR_FILE": str(jar_file),
                "C0B_FIXTURE_APP_DIR": str(app_dir),
                "C0B_FIXTURE_DASHBOARD_CURRENT": str(dashboard_current),
                "C0B_FIXTURE_DASHBOARD_RELEASE": str(release_dir),
                "C0B_FIXTURE_START_TEXT": "1970-01-01 00:33:20 UTC",
                "C0B_FIXTURE_JAR_SHA256": FAKE_JAR_SHA,
                "C0B_FIXTURE_UNIT_SHA256": (
                    "4a2c8593bef542aea068b85d2a6b8b044"
                    "ce2418aa7144cd82694d95863de6e3e"
                ),
            }
        collector_command = [str(fixture_collector)]
        if failure == "invocation":
            collector_command.append("unexpected-argument")
        if failure == "closed-pipe":
            process = subprocess.Popen(
                collector_command,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                env=command_env,
            )
            self.assertIsNotNone(process.stdout)
            process.stdout.close()
            result = subprocess.CompletedProcess(
                process.args,
                process.wait(timeout=20),
                stdout=b"",
                stderr=b"",
            )
        else:
            result = subprocess.run(
                collector_command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=command_env,
                check=False,
                timeout=20,
            )
        for path, original_bytes in immutable_files.items():
            self.assertEqual(path.read_bytes(), original_bytes)
        self.assertTrue(dashboard_current.is_symlink())
        self.assertEqual(os.readlink(dashboard_current), dashboard_target)
        observed_order = mysql_log.read_text(encoding="utf-8").splitlines()
        return result, fixture, observed_order

    def _assert_collector_host_call_vectors(
        self, fixture: Path, *, active: bool
    ) -> None:
        env_file = fixture / "etc/trading/trading.env"
        unit_file = fixture / "etc/systemd/system/trading.service"
        jar_file = fixture / "opt/trading/app/trading.jar"
        dashboard_current = fixture / "opt/trading/admin-dashboard/current"

        first_systemctl = [
            "LoadState",
            "ActiveState",
            "SubState",
            "MainPID",
            "FragmentPath",
            "DropInPaths",
            "EnvironmentFiles",
            "ExecStart",
            "WorkingDirectory",
        ]
        second_systemctl = [
            "ActiveState",
            "SubState",
            "MainPID",
            "FragmentPath",
            "DropInPaths",
            "EnvironmentFiles",
            "ExecStart",
            "WorkingDirectory",
        ]
        if active:
            first_systemctl.append("ExecMainStartTimestamp")
            second_systemctl.append("ExecMainStartTimestamp")
        self.assertEqual(
            (fixture / "systemctl.calls").read_text(encoding="utf-8").splitlines(),
            first_systemctl + second_systemctl,
        )
        self.assertEqual(
            (fixture / "sha256sum.calls").read_text(encoding="utf-8").splitlines(),
            [
                str(env_file),
                str(unit_file),
                str(jar_file),
                str(env_file),
                str(jar_file),
                str(unit_file),
            ],
        )
        expected_stat = [
            f"%Y:%s\t{env_file}",
            f"%Y:%s\t{jar_file}",
            f"%Y:%s\t{unit_file}",
        ]
        if active:
            expected_stat.extend(
                [
                    f"%Y\t{env_file}",
                    f"%Y\t{jar_file}",
                    f"%Y\t{unit_file}",
                ]
            )
        expected_stat.extend(
            [
                f"%Y:%s\t{env_file}",
                f"%Y:%s\t{jar_file}",
                f"%Y:%s\t{unit_file}",
            ]
        )
        self.assertEqual(
            (fixture / "stat.calls").read_text(encoding="utf-8").splitlines(),
            expected_stat,
        )
        self.assertEqual(
            (fixture / "readlink.calls").read_text(encoding="utf-8").splitlines(),
            [str(dashboard_current), str(dashboard_current)],
        )
        date_calls = (fixture / "date.calls").read_text(encoding="utf-8").splitlines()
        if active:
            self.assertEqual(date_calls, ["--date=1970-01-01 00:33:20 UTC\t+%s"])
        else:
            self.assertEqual(date_calls, [])

    def _assert_fixture_secrets_absent(self, *payloads: bytes) -> None:
        observable = b"".join(payloads)
        for sentinel in FIXTURE_SECRET_SENTINELS:
            self.assertNotIn(sentinel.encode("utf-8"), observable)

    def build_bundle(self, output_name: str = "c0b") -> Path:
        self.assertEqual(self.sanitize().returncode, 0)
        output = self.root / output_name
        build_bundle_atomic(
            capture_dir=self.runner_temp / "sanitized",
            output_dir=output,
            decision_file=self.decision_file,
            snapshot_at="2026-08-09T13:01:00+09:00",
            compared_at="2026-08-09T13:02:00+09:00",
            diff_collector="c0b-code-owned-comparison",
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            **self.workflow_context,
        )
        return output

    def test_item_order_is_exact(self) -> None:
        self.assertEqual(
            ITEM_IDS,
            (
                "java_code_sha",
                "effective_runtime_config",
                "db_strategy_state",
                "eod_state_pair",
                "order_mode_ownership_quantity",
                "production_provider_contract",
                "price_market_time_semantics",
                "kis_vix_ma200_semantics",
                "freshness_retry_deadline",
                "operational_comparison_tolerances",
                "approval_document_code_sha",
                "c0a_diff_summary",
            ),
        )

    def test_order_predicate_requires_a_completed_latest_job(self) -> None:
        self.assertTrue(_latest_job_is_completed("2026-08-08,COMPLETED"))
        for value in ("none", "2026-08-08,FAILED", "2026-08-08,RUNNING"):
            with self.subTest(value=value):
                self.assertFalse(_latest_job_is_completed(value))

    def test_order_predicate_requires_verified_live_process_environment(self) -> None:
        config = fake_raw_values(self.runtime_sha)["effective_runtime_config"]
        order = fake_raw_values(self.runtime_sha)["order_mode_ownership_quantity"]
        self.assertFalse(_item5_live_environment_verified(config, order))
        verified_config = dict(config)
        verified_order = dict(order)
        verified_config["live_process_environment_verification"] = (
            "VERIFIED_LIVE_PROCESS_ENVIRONMENT"
        )
        verified_order["order_contract_live_process_environment"] = (
            "VERIFIED_LIVE_PROCESS_ENVIRONMENT"
        )
        self.assertTrue(
            _item5_live_environment_verified(verified_config, verified_order)
        )

    def test_stream_sanitizer_writes_exact_atomic_capture_directory(self) -> None:
        result = self.sanitize()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual(result.stdout, b"")
        output_dir = self.runner_temp / "sanitized"
        payloads = validate_capture_directory(
            output_dir,
            CAPTURED_AT,
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            **self.workflow_context,
            decision_file=self.decision_file,
        )
        self.assertEqual(tuple(payloads), ITEM_IDS)
        self.assertEqual(len(list(output_dir.rglob("*.json"))), 12)
        serialized = "".join(
            path.read_text(encoding="utf-8") for path in output_dir.rglob("*.json")
        )
        self.assertNotIn(self.runtime_sha, serialized)
        self.assertNotIn(FAKE_JAR_SHA, serialized)
        self.assertIn("matched known production deployment", serialized)

    def test_raw_stream_never_accepts_secret_or_partial_output(self) -> None:
        values = fake_raw_values(self.runtime_sha)
        values["db_strategy_state"]["dd_bucket_parameter"] = (
            "ADOPTED:password=fixture-value"
        )
        result = self.sanitize(raw_protocol(values), "rejected")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.runner_temp / "rejected").exists())
        self.assertNotIn(b"fixture-value", result.stderr)

    def test_empty_and_partial_streams_are_distinct_and_leave_no_directory(self) -> None:
        cases = {
            "empty": (b"", b"raw stream is empty"),
            "header-only": (
                b"C0B_RAW_V1\n",
                b"raw stream ended before its terminator",
            ),
            "partial": (
                raw_protocol().replace(b"C0B_RAW_END\n", b""),
                b"raw stream ended before its terminator",
            ),
        }
        for name, (payload, diagnostic) in cases.items():
            with self.subTest(name=name):
                result = self.sanitize(payload, name)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(diagnostic, result.stderr)
                self.assertFalse((self.runner_temp / name).exists())

    def test_raw_stream_rejects_duplicate_order_terminator_and_size_attacks(self) -> None:
        lines = raw_protocol(runtime_sha=self.runtime_sha).splitlines(keepends=True)
        attacks = {
            "duplicate-record": b"".join(lines[:2] + [lines[1]] + lines[2:]),
            "out-of-order": b"".join(
                lines[:1] + [lines[2], lines[1]] + lines[3:]
            ),
            "repeated-terminator": raw_protocol(runtime_sha=self.runtime_sha)
            + b"C0B_RAW_END\n",
            "oversized-line": b"C0B_RAW_V1\n" + b"x" * 2049 + b"\n",
        }
        for name, payload in attacks.items():
            with self.subTest(name=name):
                with self.assertRaises(C0BError):
                    parse_raw_stream(io.BytesIO(payload))

    def test_existing_or_symlink_output_is_never_overwritten(self) -> None:
        existing = self.runner_temp / "existing"
        existing.mkdir()
        marker = existing / "marker"
        marker.write_text("preserve", encoding="utf-8")
        result = self.sanitize(output_name="existing")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(marker.read_text(encoding="utf-8"), "preserve")

        target = self.runner_temp / "target"
        target.mkdir()
        link = self.runner_temp / "linked"
        link.symlink_to(target, target_is_directory=True)
        result = self.sanitize(output_name="linked")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(target.iterdir()), [])

    def test_deployment_requires_both_release_and_jar_match(self) -> None:
        parsed = parse_raw_stream(io.BytesIO(raw_protocol(runtime_sha=self.runtime_sha)))
        deployments = load_known_deployments(self.known_file)
        self.assertEqual(resolve_deployment(parsed, deployments), self.runtime_sha)
        parsed["java_code_sha"]["runtime_jar_sha256"] = "f" * 64
        with self.assertRaisesRegex(C0BError, "cannot be resolved"):
            resolve_deployment(parsed, deployments)

        active = fake_raw_values(self.runtime_sha)
        active["java_code_sha"]["service_active_state"] = "active"
        active["java_code_sha"]["runtime_code_basis"] = (
            "disk jar changed or timestamp ambiguous"
        )
        with self.assertRaisesRegex(C0BError, "cannot be proven"):
            resolve_deployment(
                parse_raw_stream(io.BytesIO(raw_protocol(active))), deployments
            )

    def test_known_deployment_provenance_and_local_commit_are_required(self) -> None:
        payload = json.loads(self.known_file.read_text(encoding="utf-8"))
        payload["deployments"][0]["run_head_sha"] = "f" * 40
        invalid_known = self.root / "invalid-known.json"
        invalid_known.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(C0BError):
            load_known_deployments(invalid_known)

        self.assertEqual(self.sanitize().returncode, 0)
        unrelated = self.root / "unrelated"
        unrelated.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=unrelated, check=True)
        with self.assertRaisesRegex(C0BError, "local Git commit"):
            validate_capture_directory(
                self.runner_temp / "sanitized",
                CAPTURED_AT,
                known_deployments_path=self.known_file,
                repository_root=unrelated,
                decision_file=self.decision_file,
            )

    def test_c0a_revision_is_pinned_not_merely_consistent(self) -> None:
        self.decision_file.write_text(
            decision_record(self.runtime_sha), encoding="utf-8"
        )
        result = self.sanitize(output_name="wrong-approved-revision")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.runner_temp / "wrong-approved-revision").exists())

    def test_state_semantics_and_cross_capture_coherence_fail_closed(self) -> None:
        values = fake_raw_values(self.runtime_sha)
        values["eod_state_pair"]["latest_state"] = values["eod_state_pair"][
            "latest_state"
        ].replace(",4.0000,12.0000,", ",4.1000,12.0000,")
        with self.assertRaises(C0BError):
            parse_raw_stream(io.BytesIO(raw_protocol(values)))

        values = fake_raw_values(self.runtime_sha)
        values["eod_state_pair"]["previous_state"] = values["eod_state_pair"][
            "previous_state"
        ].replace("2026-08-07", "2026-08-08")
        result = self.sanitize(raw_protocol(values), "same-date")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.runner_temp / "same-date").exists())

        values = fake_raw_values(self.runtime_sha)
        values["eod_state_pair"]["latest_date_row_count"] = "2"
        self.assertEqual(self.sanitize(raw_protocol(values)).returncode, 0)
        output = self.root / "duplicate-date-bundle"
        build_bundle_atomic(
            capture_dir=self.runner_temp / "sanitized",
            output_dir=output,
            decision_file=self.decision_file,
            snapshot_at="2026-08-09T13:01:00+09:00",
            compared_at="2026-08-09T13:02:00+09:00",
            diff_collector="c0b-code-owned-comparison",
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            **self.workflow_context,
        )
        diff = json.loads((output / "diff.json").read_text(encoding="utf-8"))
        self.assertEqual(diff["items"][2]["result"], "DIFF")
        self.assertEqual(diff["items"][3]["result"], "DIFF")

    def test_missing_strategy_rows_are_explicit_valid_results(self) -> None:
        values = fake_raw_values(self.runtime_sha)
        for name in ("latest_as_of_date", "signal_symbol", "strategy_on", "version", "weights"):
            values["db_strategy_state"][name] = "MISSING"
        values["eod_state_pair"]["latest_state"] = "MISSING"
        values["eod_state_pair"]["latest_date_row_count"] = "0"
        values["eod_state_pair"]["previous_state"] = "MISSING"
        values["eod_state_pair"]["previous_date_row_count"] = "0"
        parse_raw_stream(io.BytesIO(raw_protocol(values)))

    def test_eod_baseline_drift_is_sanitized_and_reported_as_diff(self) -> None:
        values = fake_raw_values(self.runtime_sha)
        values["eod_state_pair"]["latest_state"] = values["eod_state_pair"][
            "latest_state"
        ].replace("LESS_THAN_15", "FROM_15_TO_25")
        result = self.sanitize(raw_protocol(values), "baseline-drift")
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        output = self.root / "baseline-drift-bundle"
        build_bundle_atomic(
            capture_dir=self.runner_temp / "baseline-drift",
            output_dir=output,
            decision_file=self.decision_file,
            snapshot_at="2026-08-09T13:01:00+09:00",
            compared_at="2026-08-09T13:02:00+09:00",
            diff_collector="c0b-code-owned-comparison",
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            **self.workflow_context,
        )
        diff = json.loads((output / "diff.json").read_text(encoding="utf-8"))
        self.assertEqual(diff["items"][2]["result"], "DIFF")
        self.assertEqual(diff["items"][3]["result"], "DIFF")

    def test_sql_guard_allows_only_one_read_query(self) -> None:
        script = str(SCRIPT_DIR / "collect_remote.sh")
        self_test = subprocess.run(
            [script, "--self-test"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        self.assertEqual(self_test.returncode, 0)
        valid = subprocess.run(
            [
                script,
                "--validate-select",
                "SELECT COUNT(*) FROM execution_order WHERE status IN ('PLANNED','REQUESTED','ACCEPTED','CONFIRMATION_REQUIRED')",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        self.assertEqual(valid.returncode, 0)
        for statement in (
            "UPDATE strategy_state SET version=2",
            "SELECT 1; DELETE FROM strategy_state",
            "SELECT SLEEP(1)",
            "SELECT * FROM mysql.user",
            "SELECT * FROM strategy_state FOR UPDATE",
            "SELECT * FROM strategy_state INTO OUTFILE '/tmp/raw'",
        ):
            result = subprocess.run(
                [script, "--validate-select", statement],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            self.assertNotEqual(result.returncode, 0, statement)

    def test_env_decoder_handles_example_quotes_without_eval(self) -> None:
        script = str(SCRIPT_DIR / "collect_remote.sh")
        for encoded, expected in (("'prod'", "prod"), ('"3s"', "3s"), ("false", "false")):
            result = subprocess.run(
                [script, "--decode-env-value", encoded],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
                text=True,
            )
            self.assertEqual(result.returncode, 0)
            self.assertEqual(result.stdout, expected)
        malformed = subprocess.run(
            [script, "--decode-env-value", "'prod"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        self.assertNotEqual(malformed.returncode, 0)

    def test_env_file_parser_is_canonical_and_rejects_duplicates(self) -> None:
        script = str(SCRIPT_DIR / "collect_remote.sh")
        fixtures = {
            "valid": ("KEY='prod'\n# comment\nOTHER=value\n", 0),
            "leading-space": (" KEY=prod\n", 1),
            "export": ("export KEY=prod\n", 1),
            "duplicate": ("KEY=prod\nKEY=default\n", 1),
            "continuation": ("KEY=prod\\\n", 1),
            "leading-comment": ("  # comment\nKEY=prod\n", 1),
        }
        for name, (content, expected_failure) in fixtures.items():
            path = self.root / f"{name}.env"
            path.write_text(content, encoding="utf-8")
            result = subprocess.run(
                [script, "--validate-env-file", str(path), "KEY"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            with self.subTest(name=name):
                self.assertEqual(result.returncode != 0, bool(expected_failure))

    def test_prod_env_key_allowlist_rejects_relaxed_binding_aliases(self) -> None:
        script = str(SCRIPT_DIR / "collect_remote.sh")
        accepted = self.root / "prod-accepted.env"
        accepted.write_text(
            "SPRING_PROFILES_ACTIVE=prod\n"
            "SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/trading\n"
            "KIS_BASE_URL=https://openapi.koreainvestment.com:9443\n"
            "KIS_CONNECT_TIMEOUT=3s\n"
            "TRADING_CIRCUIT_BREAKER_MA_PERIOD=200\n",
            encoding="utf-8",
        )
        self.assertEqual(
            subprocess.run(
                [script, "--validate-prod-env-file", str(accepted)],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            ).returncode,
            0,
        )
        for index, alias in enumerate(
            (
                "KIS_BASEURL=value",
                "KIS_CONNECTTIMEOUT=3s",
                "TRADING_CIRCUITBREAKER_MA_PERIOD=200",
                "TRADING_MARKETCALENDAR_MARKET_ZONE_ID=UTC",
                "UNKNOWN_KEY=value",
            )
        ):
            rejected = self.root / f"prod-rejected-{index}.env"
            rejected.write_text(alias + "\n", encoding="utf-8")
            result = subprocess.run(
                [script, "--validate-prod-env-file", str(rejected)],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            with self.subTest(alias=alias):
                self.assertNotEqual(result.returncode, 0)

    def test_remote_script_has_fixed_read_only_session_and_no_kis_credentials(self) -> None:
        script = (SCRIPT_DIR / "collect_remote.sh").read_text(encoding="utf-8")
        self.assertIn("--init-command='SET SESSION TRANSACTION READ ONLY'", script)
        self.assertNotIn("read_env_raw KIS_APP_KEY", script)
        self.assertNotIn("read_env_raw KIS_APP_SECRET", script)
        self.assertNotIn("read_env_raw KIS_ACCOUNT_NO", script)
        self.assertNotIn("curl ", script)
        self.assertNotIn("wget ", script)
        self.assertNotIn("SUM(quantity)", script)
        self.assertIn("decision_config_override_contract", script)
        self.assertIn("TRADINGMARKETCALENDAR", script)
        self.assertIn("service_main_pid_after", script)
        self.assertIn('"${service_main_pid}" == "${service_main_pid_after}"', script)
        self.assertIn("readonly ENV_FILE=/etc/trading/trading.env", script)
        self.assertIn("readonly PROD_JAR=/opt/trading/app/trading.jar", script)
        self.assertIn(
            "readonly DASHBOARD_CURRENT=/opt/trading/admin-dashboard/current", script
        )
        self.assertIn('[[ "${EUID}" == 0 ]] || die', script)
        self.assertNotIn("C0B_FIXTURE", script)

    def test_collector_main_fixture_emits_complete_sanitizable_stream(self) -> None:
        result, fixture, observed_order = self._run_collector_fixture()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual(result.stderr, b"")

        expected = fake_raw_values(self.runtime_sha)
        expected["effective_runtime_config"]["decision_config_override_contract"] = (
            "UNVERIFIED_OVERRIDE_PRESENT"
        )
        expected["effective_runtime_config"]["file_and_unit_override_status"] = (
            "UNVERIFIED_OVERRIDE_PRESENT"
        )
        expected_protocol = raw_protocol(expected, self.runtime_sha)
        self.assertEqual(result.stdout, expected_protocol)
        self.assertEqual(len(result.stdout.splitlines()), 145)
        self.assertEqual(parse_raw_stream(io.BytesIO(result.stdout)), expected)
        self.assertEqual(observed_order, self._collector_fixture_sql()[1])
        self._assert_collector_host_call_vectors(fixture, active=False)

        observable = (
            result.stdout
            + result.stderr
            + (fixture / "mysql.calls").read_bytes()
            + (fixture / "systemctl.calls").read_bytes()
        )
        self._assert_fixture_secrets_absent(observable)

        sanitized = self.sanitize(result.stdout, "collector-main")
        self.assertEqual(sanitized.returncode, 0, sanitized.stderr.decode())
        payloads = validate_capture_directory(
            self.runner_temp / "collector-main",
            CAPTURED_AT,
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            decision_file=self.decision_file,
            **self.workflow_context,
        )
        self.assertEqual(tuple(payloads), ITEM_IDS)
        self.assertEqual(
            len(list((self.runner_temp / "collector-main").rglob("*.json"))), 12
        )
        self._assert_fixture_secrets_absent(
            sanitized.stdout,
            sanitized.stderr,
            *(
                path.read_bytes()
                for path in sorted(
                    (self.runner_temp / "collector-main").rglob("*.json")
                )
            ),
        )

    def test_collector_active_main_fixture_covers_timestamp_branch(self) -> None:
        result, fixture, observed_order = self._run_collector_fixture(active=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual(result.stderr, b"")

        expected = fake_raw_values(self.runtime_sha)
        expected["java_code_sha"]["service_active_state"] = "active"
        expected["java_code_sha"]["runtime_code_basis"] = (
            "running process jar predates start"
        )
        expected["effective_runtime_config"]["service_sub_state"] = "running"
        expected["effective_runtime_config"]["runtime_config_basis"] = (
            "running process matches file timestamps"
        )
        expected["effective_runtime_config"]["decision_config_override_contract"] = (
            "UNVERIFIED_OVERRIDE_PRESENT"
        )
        expected["effective_runtime_config"]["file_and_unit_override_status"] = (
            "UNVERIFIED_OVERRIDE_PRESENT"
        )
        self.assertEqual(result.stdout, raw_protocol(expected, self.runtime_sha))
        self.assertEqual(parse_raw_stream(io.BytesIO(result.stdout)), expected)
        self.assertEqual(observed_order, self._collector_fixture_sql()[1])
        self._assert_collector_host_call_vectors(fixture, active=True)

        sanitized = self.sanitize(result.stdout, "collector-active-main")
        self.assertEqual(sanitized.returncode, 0, sanitized.stderr.decode())
        payloads = validate_capture_directory(
            self.runner_temp / "collector-active-main",
            CAPTURED_AT,
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            decision_file=self.decision_file,
            **self.workflow_context,
        )
        self.assertEqual(tuple(payloads), ITEM_IDS)
        self._assert_fixture_secrets_absent(
            result.stdout,
            result.stderr,
            sanitized.stdout,
            sanitized.stderr,
            *(
                path.read_bytes()
                for path in sorted(
                    (self.runner_temp / "collector-active-main").rglob("*.json")
                )
            ),
        )

    def test_collector_diagnostic_exit_codes_are_unique_and_fixed(self) -> None:
        source = (SCRIPT_DIR / "collect_remote.sh").read_text(encoding="utf-8")
        observed = {
            name: int(value)
            for name, value in re.findall(
                r"^readonly (C0B_EXIT_[A-Z_]+)=([0-9]+)$",
                source,
                re.MULTILINE,
            )
        }
        expected = {
            "C0B_EXIT_INVOCATION": 41,
            "C0B_EXIT_RUNTIME": 42,
            "C0B_EXIT_CONFIG": 43,
            "C0B_EXIT_DB_INITIAL": 44,
            "C0B_EXIT_DB_CONSISTENCY": 45,
            "C0B_EXIT_HOST_STABILITY": 46,
            "C0B_EXIT_PROTOCOL": 47,
            "C0B_EXIT_ENV_FILE": 48,
            "C0B_EXIT_JAR_FILE": 49,
            "C0B_EXIT_ENV_SHAPE": 50,
            "C0B_EXIT_ENV_ALLOWLIST": 51,
            "C0B_EXIT_MYSQL_CLIENT": 52,
            "C0B_EXIT_SYSTEMCTL": 53,
            "C0B_EXIT_INSPECTION_TOOLS": 54,
            "C0B_EXIT_FINGERPRINT": 55,
            "C0B_EXIT_OVERRIDE_SCAN": 56,
            "C0B_EXIT_DB_ENV": 57,
            "C0B_EXIT_DB_URL": 58,
        }
        self.assertEqual(observed, expected)
        self.assertEqual(len(set(observed.values())), len(observed))

    def test_collector_failures_are_phase_coded_secret_free_and_atomic(self) -> None:
        failures = {
            "invocation": 41,
            "runtime": 42,
            "config": 43,
            "database": 44,
            "consistency": 45,
            "host-stability": 46,
            "protocol": 47,
            "closed-pipe": 47,
            "env-file": 48,
            "jar-file": 49,
            "env-shape": 50,
            "env-allowlist": 51,
            "mysql-client": 52,
            "systemctl": 53,
            "inspection-tools": 54,
            "fingerprint": 55,
            "override-scan": 56,
            "db-env": 57,
            "db-url": 58,
        }
        pre_read_failures = {
            "invocation",
            "env-file",
            "jar-file",
            "env-shape",
            "env-allowlist",
            "mysql-client",
            "systemctl",
            "inspection-tools",
            "fingerprint",
            "override-scan",
            "db-env",
            "db-url",
        }
        for failure, expected_status in failures.items():
            with self.subTest(failure=failure):
                result, fixture, observed_order = self._run_collector_fixture(failure)
                self.assertEqual(result.returncode, expected_status)
                self.assertNotIn(b"C0B_RAW_END", result.stdout)
                if failure == "protocol":
                    self.assertTrue(result.stdout.startswith(b"C0B_RAW_V1\n"))
                else:
                    self.assertEqual(result.stdout, b"")
                if failure != "closed-pipe":
                    stderr_lines = result.stderr.decode().splitlines()
                    self.assertEqual(
                        stderr_lines.count(
                            "C0-B remote collection failed at a fixed diagnostic phase"
                        ),
                        1,
                    )
                    self.assertTrue(
                        set(stderr_lines).issubset(
                            {
                                "C0-B remote collection rejected by safety guard",
                                "C0-B remote collection failed at a fixed diagnostic phase",
                            }
                        )
                    )
                observable = (
                    result.stdout
                    + result.stderr
                    + (fixture / "mysql.calls").read_bytes()
                    + (fixture / "systemctl.calls").read_bytes()
                )
                self._assert_fixture_secrets_absent(observable)
                if failure in pre_read_failures:
                    self.assertEqual(observed_order, [])
                    self.assertEqual(
                        (fixture / "systemctl.calls").read_text(encoding="utf-8"),
                        "",
                    )

                output_name = f"collector-failure-{failure}"
                sanitized = self.sanitize(result.stdout, output_name)
                self.assertNotEqual(sanitized.returncode, 0)
                self.assertFalse((self.runner_temp / output_name).exists())
                self._assert_fixture_secrets_absent(
                    sanitized.stdout, sanitized.stderr
                )

    def test_pipeline_status_capture_preserves_both_exit_codes(self) -> None:
        pairs = tuple((status, 1) for status in range(41, 59)) + (
            (141, 1),
            (0, 1),
            (255, 1),
            (0, 0),
        )
        for left, right in pairs:
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    (
                        'set +e; (exit "$1") | (exit "$2"); '
                        'statuses=("${PIPESTATUS[@]}"); '
                        'printf "%s %s\\n" "${statuses[0]}" "${statuses[1]}"'
                    ),
                    "fixture",
                    str(left),
                    str(right),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                text=True,
            )
            with self.subTest(left=left, right=right):
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), f"{left} {right}")

    def test_remote_emissions_exactly_match_raw_fact_order(self) -> None:
        script = (SCRIPT_DIR / "collect_remote.sh").read_text(encoding="utf-8")
        emitted = re.findall(
            r"^\s*emit ([a-z0-9_]+) ([a-z0-9_]+)", script, flags=re.MULTILINE
        )
        expected = [
            (item_id, name)
            for item_id in ITEM_IDS[:10]
            for name in RAW_FACTS[item_id]
        ]
        self.assertEqual(emitted, expected)

    def test_validation_binds_capture_and_bundle_to_expected_workflow_context(self) -> None:
        self.assertEqual(self.sanitize().returncode, 0)
        expected = {
            "expected_workflow_sha": self.workflow_sha,
            "expected_workflow_run_id": "12345678901",
            "expected_workflow_run_attempt": "1",
        }
        validate_capture_directory(
            self.runner_temp / "sanitized",
            CAPTURED_AT,
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            decision_file=self.decision_file,
            **expected,
        )
        with self.assertRaisesRegex(C0BError, "expected workflow context"):
            validate_capture_directory(
                self.runner_temp / "sanitized",
                CAPTURED_AT,
                known_deployments_path=self.known_file,
                repository_root=self.fake_repository,
                decision_file=self.decision_file,
                expected_workflow_sha=self.workflow_sha,
                expected_workflow_run_id="12345678902",
                expected_workflow_run_attempt="1",
            )
        output = self.root / "context-bound-bundle"
        build_bundle_atomic(
            capture_dir=self.runner_temp / "sanitized",
            output_dir=output,
            decision_file=self.decision_file,
            snapshot_at="2026-08-09T13:01:00+09:00",
            compared_at="2026-08-09T13:02:00+09:00",
            diff_collector="c0b-code-owned-comparison",
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            **self.workflow_context,
        )
        validate_bundle_directory(
            output,
            decision_file=self.decision_file,
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            **expected,
        )

    def test_bundle_builder_rejects_omitted_or_mismatched_workflow_context(self) -> None:
        self.assertEqual(self.sanitize().returncode, 0)
        common = {
            "capture_dir": self.runner_temp / "sanitized",
            "decision_file": self.decision_file,
            "snapshot_at": "2026-08-09T13:01:00+09:00",
            "compared_at": "2026-08-09T13:02:00+09:00",
            "diff_collector": "c0b-code-owned-comparison",
            "known_deployments_path": self.known_file,
            "repository_root": self.fake_repository,
        }
        with self.assertRaises(TypeError):
            build_bundle_atomic(
                output_dir=self.root / "omitted-context",
                **common,
            )
        with self.assertRaisesRegex(C0BError, "expected workflow context"):
            build_bundle_atomic(
                output_dir=self.root / "mismatched-context",
                expected_workflow_sha=self.workflow_sha,
                expected_workflow_run_id="12345678902",
                expected_workflow_run_attempt="1",
                **common,
            )
        self.assertFalse((self.root / "omitted-context").exists())
        self.assertFalse((self.root / "mismatched-context").exists())

    def test_validation_clis_require_workflow_context_arguments(self) -> None:
        capture_command = [
            sys.executable,
            str(SCRIPT_DIR / "validate_evidence.py"),
            "captures",
            "--capture-dir",
            str(self.root / "unused-captures"),
            "--captured-at",
            CAPTURED_AT,
        ]
        build_command = [
            sys.executable,
            str(SCRIPT_DIR / "build_evidence.py"),
            "--capture-dir",
            str(self.root / "unused-captures"),
            "--output-dir",
            str(self.root / "unused-bundle"),
            "--snapshot-at",
            CAPTURED_AT,
            "--compared-at",
            CAPTURED_AT,
        ]
        for command in (capture_command, build_command):
            with self.subTest(script=Path(command[1]).name):
                result = subprocess.run(
                    command,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                    check=False,
                )
                self.assertEqual(result.returncode, 2)

    def test_bundle_builder_generates_checksums_diff_and_real_summary(self) -> None:
        sanitized = self.runner_temp / "sanitized"
        self.assertEqual(self.sanitize().returncode, 0)
        output = self.root / "c0b"
        build_bundle_atomic(
            capture_dir=sanitized,
            output_dir=output,
            decision_file=self.decision_file,
            snapshot_at="2026-08-09T13:01:00+09:00",
            compared_at="2026-08-09T13:02:00+09:00",
            diff_collector="c0b-code-owned-comparison",
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            **self.workflow_context,
        )
        snapshot_sha, diff_sha = validate_bundle_directory(
            output,
            decision_file=self.decision_file,
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
        )
        self.assertEqual(len(snapshot_sha), 64)
        self.assertEqual(len(diff_sha), 64)
        diff_payload = json.loads((output / "diff.json").read_text(encoding="utf-8"))
        self.assertEqual(diff_payload["result"], "DIFF")
        summary_payload = json.loads(
            (output / "items/c0a_diff_summary.json").read_text(encoding="utf-8")
        )
        facts = {fact["name"]: fact["value"] for fact in summary_payload["data"]["facts"]}
        self.assertEqual(facts["substantive aggregate result"], "DIFF")
        substantive = diff_payload["items"][:-1]
        diff_count = sum(item["result"] == "DIFF" for item in substantive)
        self.assertEqual(facts["different substantive item count"], str(diff_count))
        self.assertEqual(
            facts["matching substantive item count"], str(11 - diff_count)
        )
        self.assertGreater(diff_count, 0)
        self.assertNotIn("comparison plan required", json.dumps(summary_payload))

    def test_bundle_time_order_failure_is_atomic(self) -> None:
        sanitized = self.runner_temp / "sanitized"
        self.assertEqual(self.sanitize().returncode, 0)
        output = self.root / "invalid-bundle"
        with self.assertRaisesRegex(C0BError, "time order"):
            build_bundle_atomic(
                capture_dir=sanitized,
                output_dir=output,
                decision_file=self.decision_file,
                snapshot_at="2026-08-09T13:01:00+09:00",
                compared_at="2026-08-09T12:59:00+09:00",
                diff_collector="c0b-code-owned-comparison",
                known_deployments_path=self.known_file,
                repository_root=self.fake_repository,
                **self.workflow_context,
            )
        self.assertFalse(output.exists())

    def test_bundle_validation_recomputes_code_owned_results_without_plan(self) -> None:
        output = self.build_bundle()
        detail_path = output / "diff-items/java_code_sha.json"
        detail = json.loads(detail_path.read_text(encoding="utf-8"))
        detail["result"] = "NO_DIFF"
        detail["summary"] = "Known production deployment and active running JAR evidence match the runtime predicate."
        detail_bytes = (
            json.dumps(detail, ensure_ascii=False, separators=(",", ":")) + "\n"
        ).encode()
        detail_path.write_bytes(detail_bytes)
        detail_sha = hashlib.sha256(detail_bytes).hexdigest()

        diff_path = output / "diff.json"
        diff = json.loads(diff_path.read_text(encoding="utf-8"))
        java_item = diff["items"][0]
        java_item["result"] = "NO_DIFF"
        java_item["details_sha256"] = detail_sha
        java_item["details_source"] = (
            "docs/v2-cutover/evidence/c0b/diff-items/java_code_sha.json"
            f"#sha256={detail_sha}"
        )
        diff_path.write_text(
            json.dumps(diff, ensure_ascii=False, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(C0BError, "deterministic comparison"):
            validate_bundle_directory(
                output,
                decision_file=self.decision_file,
                known_deployments_path=self.known_file,
                repository_root=self.fake_repository,
            )

    def test_bundle_checksum_tamper_and_approval_provenance_tamper_fail(self) -> None:
        output = self.build_bundle()
        item_path = output / "items/production_provider_contract.json"
        item_path.write_bytes(item_path.read_bytes() + b" ")
        with self.assertRaises(C0BError):
            validate_bundle_directory(
                output,
                decision_file=self.decision_file,
                known_deployments_path=self.known_file,
                repository_root=self.fake_repository,
            )

        approval_path = (
            self.runner_temp
            / "sanitized/items/approval_document_code_sha.json"
        )
        approval = json.loads(approval_path.read_text(encoding="utf-8"))
        approval["data"]["facts"][16]["value"] = "fffff"
        approval_path.write_text(
            json.dumps(approval, ensure_ascii=False, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(C0BError, "local Git commit"):
            validate_capture_directory(
                self.runner_temp / "sanitized",
                CAPTURED_AT,
                known_deployments_path=self.known_file,
                repository_root=self.fake_repository,
                decision_file=self.decision_file,
            )

    def test_transport_round_trip_and_split_job_outputs_preserve_exact_bytes(self) -> None:
        bundle = self.build_bundle()
        archive = self.root / "bundle.tar.gz"
        roundtrip = self.root / "roundtrip"
        transport.pack_bundle(bundle, archive, roundtrip)
        self.assertEqual(
            transport._bundle_payloads(roundtrip),
            transport._bundle_payloads(bundle),
        )
        validate_bundle_directory(
            roundtrip,
            decision_file=self.decision_file,
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            **self.workflow_context,
        )

        github_output = self.root / "github-output"
        transport.emit_outputs(archive, github_output)
        outputs = dict(
            line.split("=", 1)
            for line in github_output.read_text(encoding="ascii").splitlines()
        )
        self.assertEqual(
            set(outputs),
            {
                "archive_b64_0",
                "archive_b64_1",
                "archive_b64_2",
                "archive_b64_3",
                "archive_sha256",
                "archive_bytes",
                "archive_b64_chars",
            },
        )
        environment = {
            f"C0B_ARCHIVE_B64_{index}": outputs[f"archive_b64_{index}"]
            for index in range(transport.OUTPUT_CHUNK_COUNT)
        }
        environment.update(
            {
                "C0B_ARCHIVE_SHA256": outputs["archive_sha256"],
                "C0B_ARCHIVE_BYTES": outputs["archive_bytes"],
                "C0B_ARCHIVE_B64_CHARS": outputs["archive_b64_chars"],
            }
        )
        received_archive = self.root / "received.tar.gz"
        received = self.root / "received"
        with mock.patch.dict(os.environ, environment, clear=False):
            transport.receive_outputs(received_archive, received)
        self.assertEqual(received_archive.read_bytes(), archive.read_bytes())
        self.assertEqual(
            transport._bundle_payloads(received),
            transport._bundle_payloads(bundle),
        )
        validate_bundle_directory(
            received,
            decision_file=self.decision_file,
            known_deployments_path=self.known_file,
            repository_root=self.fake_repository,
            **self.workflow_context,
        )

    def test_transport_is_deterministic_and_rejects_concatenated_gzip(self) -> None:
        bundle = self.build_bundle()
        first = self.root / "first.tar.gz"
        second = self.root / "second.tar.gz"
        transport.pack_bundle(bundle, first, self.root / "first-roundtrip")
        transport.pack_bundle(bundle, second, self.root / "second-roundtrip")
        self.assertEqual(first.read_bytes(), second.read_bytes())

        concatenated = first.read_bytes() + second.read_bytes()
        with self.assertRaisesRegex(C0BError, "concatenated"):
            transport._archive_payloads(concatenated)

    def test_transport_rejects_path_link_and_metadata_attacks(self) -> None:
        def single_member_archive(
            member: tarfile.TarInfo, payload: bytes = b"{}\n"
        ) -> bytes:
            output = io.BytesIO()
            with tarfile.open(
                fileobj=output, mode="w", format=tarfile.USTAR_FORMAT
            ) as archive:
                member.size = len(payload) if member.isreg() else 0
                archive.addfile(member, io.BytesIO(payload) if member.isreg() else None)
            return transport._canonical_gzip_bytes(output.getvalue())

        traversal = tarfile.TarInfo("../snapshot.json")
        traversal.mode = 0o644
        traversal.uid = traversal.gid = traversal.mtime = 0
        with self.assertRaises(C0BError):
            transport._archive_payloads(single_member_archive(traversal))

        payloads = transport._bundle_payloads(self.build_bundle("mode-bundle"))

        def exact_member_attack(kind: str) -> bytes:
            output = io.BytesIO()
            archive_format = (
                tarfile.PAX_FORMAT if kind == "pax" else tarfile.USTAR_FORMAT
            )
            with tarfile.open(
                fileobj=output, mode="w", format=archive_format
            ) as archive:
                for index, relative_path in enumerate(
                    transport.EXPECTED_RELATIVE_PATHS
                ):
                    payload = payloads[relative_path]
                    member = tarfile.TarInfo(relative_path)
                    member.size = len(payload)
                    member.mode = 0o644
                    member.uid = member.gid = member.mtime = 0
                    member.uname = member.gname = ""
                    if index == 0 and kind == "mode":
                        member.mode = 0o600
                    if index == 0 and kind == "link":
                        member.type = tarfile.SYMTYPE
                        member.linkname = "../../outside"
                        member.size = 0
                    if index == 0 and kind == "pax":
                        member.pax_headers = {"comment": "unexpected"}
                    archive.addfile(
                        member,
                        None if member.issym() else io.BytesIO(payload),
                    )
            return transport._canonical_gzip_bytes(output.getvalue())

        for kind in ("mode", "link", "pax"):
            with self.subTest(kind=kind):
                with self.assertRaises(C0BError):
                    transport._archive_payloads(exact_member_attack(kind))

    def test_transport_rejects_missing_or_tampered_job_output(self) -> None:
        bundle = self.build_bundle()
        archive = self.root / "bundle.tar.gz"
        transport.pack_bundle(bundle, archive, self.root / "roundtrip")
        github_output = self.root / "github-output"
        transport.emit_outputs(archive, github_output)
        outputs = dict(
            line.split("=", 1)
            for line in github_output.read_text(encoding="ascii").splitlines()
        )
        environment = {
            f"C0B_ARCHIVE_B64_{index}": outputs[f"archive_b64_{index}"]
            for index in range(transport.OUTPUT_CHUNK_COUNT)
        }
        environment.update(
            {
                "C0B_ARCHIVE_SHA256": outputs["archive_sha256"],
                "C0B_ARCHIVE_BYTES": outputs["archive_bytes"],
                "C0B_ARCHIVE_B64_CHARS": outputs["archive_b64_chars"],
            }
        )
        attacks = {
            "truncated": {
                **environment,
                "C0B_ARCHIVE_B64_0": environment["C0B_ARCHIVE_B64_0"][1:],
            },
            "missing": {
                key: value
                for key, value in environment.items()
                if key != "C0B_ARCHIVE_B64_0"
            },
            "swapped": {
                **environment,
                "C0B_ARCHIVE_B64_0": environment["C0B_ARCHIVE_B64_1"],
                "C0B_ARCHIVE_B64_1": environment["C0B_ARCHIVE_B64_0"],
            },
        }
        for name, attacked_environment in attacks.items():
            received_archive = self.root / f"{name}.tar.gz"
            received = self.root / name
            with self.subTest(name=name):
                with mock.patch.dict(
                    os.environ, attacked_environment, clear=True
                ):
                    with self.assertRaises(C0BError):
                        transport.receive_outputs(received_archive, received)
                self.assertFalse(received_archive.exists())
                self.assertFalse(received.exists())

        archive_bytes = bytearray(archive.read_bytes())
        for index, value in ((8, 0), (9, 3)):
            malformed = bytearray(archive_bytes)
            malformed[index] = value
            with self.subTest(header_index=index):
                with self.assertRaisesRegex(C0BError, "header"):
                    transport._archive_payloads(bytes(malformed))

    def test_transport_chunk_boundary_is_fixed_and_bounded(self) -> None:
        encoded = "A" * (transport.OUTPUT_CHUNK_CHARS * 2 + 4)
        parts = transport._split_encoded(encoded)
        self.assertEqual(
            [len(part) for part in parts],
            [transport.OUTPUT_CHUNK_CHARS, transport.OUTPUT_CHUNK_CHARS, 4, 0],
        )
        with self.assertRaises(C0BError):
            transport._split_encoded("A" * (transport.MAX_BASE64_CHARS + 4))

    def test_transport_four_nonempty_chunks_round_trip_end_to_end(self) -> None:
        bundle = self.root / "large-bundle"
        bundle.mkdir()
        (bundle / "diff-items").mkdir()
        (bundle / "items").mkdir()
        for relative_path in transport.EXPECTED_RELATIVE_PATHS:
            (bundle / relative_path).write_bytes(os.urandom(6_000))

        archive = self.root / "large.tar.gz"
        transport.pack_bundle(bundle, archive, self.root / "large-roundtrip")
        github_output = self.root / "large-github-output"
        transport.emit_outputs(archive, github_output)
        outputs = dict(
            line.split("=", 1)
            for line in github_output.read_text(encoding="ascii").splitlines()
        )
        self.assertEqual(
            [len(outputs[f"archive_b64_{index}"]) for index in range(4)][:3],
            [transport.OUTPUT_CHUNK_CHARS] * 3,
        )
        self.assertGreater(len(outputs["archive_b64_3"]), 0)
        environment = {
            f"C0B_ARCHIVE_B64_{index}": outputs[f"archive_b64_{index}"]
            for index in range(transport.OUTPUT_CHUNK_COUNT)
        }
        environment.update(
            {
                "C0B_ARCHIVE_SHA256": outputs["archive_sha256"],
                "C0B_ARCHIVE_BYTES": outputs["archive_bytes"],
                "C0B_ARCHIVE_B64_CHARS": outputs["archive_b64_chars"],
            }
        )
        restored = self.root / "large-restored"
        with mock.patch.dict(os.environ, environment, clear=True):
            transport.receive_outputs(self.root / "large-received.tar.gz", restored)
        self.assertEqual(
            transport._bundle_payloads(restored),
            transport._bundle_payloads(bundle),
        )

    def test_safe_detector_matches_repository_restrictions(self) -> None:
        for unsafe in (
            "password=fixture",
            "Bearer fixturevalue",
            "fixture@example.invalid",
            "1234-5678-9012",
            "1234.5678.9012",
            "abcd-efgh-ijkl-mnop-qrst",
            "A" * 32,
            "ｐassword=fixture",
            r"escaped\|pipe",
        ):
            with self.assertRaises(C0BError):
                safe_evidence_text(unsafe, "fixture")


if __name__ == "__main__":
    unittest.main()
