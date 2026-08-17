"""C0-B evidence contract and fail-closed validation helpers.

The collection path deliberately treats the SSH stream as untrusted input.  It
never needs a raw file: callers parse the stream in memory, validate an exact
allowlist, and only then write the sanitized evidence directory atomically.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unicodedata
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path
from typing import BinaryIO, Iterable


class C0BError(ValueError):
    """Raised when collection or evidence violates the C0-B contract."""


ITEMS: tuple[tuple[str, str], ...] = (
    ("java_code_sha", "Java 실행 코드 SHA"),
    ("effective_runtime_config", "effective 런타임 설정"),
    ("db_strategy_state", "DB 전략 on/off·파라미터"),
    ("eod_state_pair", "최신/바로 전 EOD 상태"),
    ("order_mode_ownership_quantity", "주문 모드·소유권·수량 설정"),
    ("production_provider_contract", "production provider·조회 계약/version"),
    (
        "price_market_time_semantics",
        "가격 종류·시장 기준일·observed/available 시각 의미",
    ),
    (
        "kis_vix_ma200_semantics",
        "KIS base 실제 기준일·VIX spot·MA200 세션/adjustment",
    ),
    ("freshness_retry_deadline", "신선도·재시도·deadline 최종값"),
    (
        "operational_comparison_tolerances",
        "operational 비교 시간창·필드별 값 허용 기준·반올림",
    ),
    (
        "approval_document_code_sha",
        "승인 당시 요구사항·명세 문서/v2 코드 SHA",
    ),
    ("c0a_diff_summary", "C0-A 대비 diff 요약"),
)
ITEM_IDS = tuple(item_id for item_id, _ in ITEMS)
ITEM_LABELS = dict(ITEMS)
REMOTE_ITEM_IDS = ITEM_IDS[:10]
DEFAULT_KNOWN_DEPLOYMENTS = Path(__file__).resolve().with_name("known_deployments.json")
DEFAULT_REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_DECISION_FILE = DEFAULT_REPOSITORY_ROOT / "docs/v2-cutover/C0_DECISIONS.md"
EXPECTED_COLLECTOR = "github-actions-prod-read-only"
EXPECTED_DIFF_COLLECTOR = "c0b-code-owned-comparison"
APPROVED_C0A_DOCUMENT_SHA = "a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c"
APPROVED_C0A_CODE_SHA = "a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c"
PINNED_RUNTIME_SHA = "5510399f8a576efc6450a47a195f0cb61869675e"
PINNED_RUNTIME_JAR_SHA256 = (
    "8f5061fcc4484e11b8447f8da279d4746a5d717d38c3b10e3ee20070edd68cf1"
)
PINNED_DEPLOY_RUN_ID = "26564469535"
PINNED_SOURCE_BLOBS: dict[str, str] = {
    "src/main/java/my/side/trading/adapter/in/scheduler/StrategyEodScheduler.java": "4d2a32450d620703e82d73675e82ce43402a1927",
    "src/main/java/my/side/trading/adapter/out/kis/client/KisOverseasQuotedPriceService.java": "b345dc180d3abd5092661afba2128ba997d2b078",
    "src/main/java/my/side/trading/adapter/out/kis/config/KisProps.java": "01462f38ae28b5e64adf4e9900456c378e532043",
    "src/main/java/my/side/trading/adapter/out/kis/config/WebClientConfig.java": "36a0ec6d635b7b1630e43988ddc6cd914f4e5090",
    "src/main/java/my/side/trading/adapter/out/kis/dto/QuotedPriceResponse.java": "7b414b1ab7b6785f2a1818ddff1343cbdbab5482",
    "src/main/java/my/side/trading/adapter/out/yahoo/YahooVixService.java": "6da62e6faf3eb9187ec511391ebdb6cd1b2753e1",
    "src/main/java/my/side/trading/adapter/out/yahoo/config/YahooWebClientConfig.java": "d69cb7d5685797a7b67a7036b9033c8c94425923",
    "src/main/java/my/side/trading/adapter/out/yahoo/dto/YahooQuoteResponse.java": "80ff9ddc07fa8b08492e5717736461252b63f012",
    "src/main/java/my/side/trading/adapter/out/kis/account/KisOverseasAccountReader.java": "9db8f668442f002d123de1b275794a771c4b03e2",
    "src/main/java/my/side/trading/core/application/execution/ExecutionOrderFactory.java": "36a32dfecc65eded9c74bd4df09271b4602617ba",
    "src/main/java/my/side/trading/core/application/execution/ExecutionRiskLimitService.java": "5bf267ff91c129dd9e5ab9918d89bf3692a73f36",
    "src/main/java/my/side/trading/core/application/execution/RebalanceDecisionService.java": "11c77f1c96b298ce5c9a6975c61294bf7e4dd7f4",
    "src/main/java/my/side/trading/core/application/execution/RebalanceOrderPlanner.java": "6297947017bfbcdbdc871c4a6d2c9990d3ea6d6a",
    "src/main/java/my/side/trading/core/application/execution/RetryableOrderExecutor.java": "4a472f670c607f222a7738bc1ee63d1d9618b5ad",
    "src/main/java/my/side/trading/core/application/execution/pricing/MarketLikePricingPolicy.java": "d997ac6f67427a61816549f2a08f5ebd2db7af50",
    "src/main/java/my/side/trading/core/application/portfolio/PortfolioService.java": "f287d982fce2c5338b049dabe43fc5955997c8c0",
    "src/main/java/my/side/trading/core/infrastructure/config/TradingStrategyProps.java": "8448d6e2d11c6d0b5baddcc0f330cbbc80acb52d",
    "src/main/java/my/side/trading/core/infrastructure/config/TradingCircuitBreakerProps.java": "15a294beefd4f3e74e2fa74294715c5ffa614c40",
    "src/main/java/my/side/trading/core/infrastructure/config/TradingFxProps.java": "eab5897010ed6c87c64733e2261ac7d80f73ddfa",
    "src/main/java/my/side/trading/core/infrastructure/config/TradingOperationProps.java": "ae53a22c4083f9671f50dedc998f1fdaa6122062",
    "src/main/java/my/side/trading/core/infrastructure/config/TradingPricingProps.java": "656ee68b89428ad99ab19aeb3a586bb034dbe637",
    "src/main/resources/application.yml": "59b37fcea16105ef52efcfd7ee431b184a4f7d94",
    "src/main/resources/application-prod.yml": "d301aaec5ae817c01cda1e8e91cb970c6d30b737",
}
FINAL_DIFF_SUMMARY = "코드 소유 C0-A 비교의 12개 결과를 정제된 집계로 기록했다."

RAW_FACTS: dict[str, tuple[str, ...]] = {
    "java_code_sha": (
        "service_active_state",
        "runtime_code_basis",
        "runtime_release_sha",
        "runtime_jar_sha256",
    ),
    "effective_runtime_config": (
        "spring_profile",
        "scheduling_enabled",
        "execution_enabled",
        "operation_mode",
        "server_binding",
        "service_sub_state",
        "environment_file_contract",
        "exec_start_contract",
        "unit_fragment_contract",
        "drop_in_contract",
        "working_directory_contract",
        "external_config_contract",
        "file_and_unit_override_status",
        "env_override_contract",
        "decision_config_override_contract",
        "runtime_config_basis",
        "live_process_environment_verification",
    ),
    "db_strategy_state": (
        "latest_as_of_date",
        "signal_symbol",
        "strategy_on",
        "version",
        "weights",
        "dd_bucket_parameter",
        "recovery_rule_parameter",
        "rebalance_tolerance_parameter",
        "vix_threshold_parameter",
        "ma_200_guard_parameter",
        "order_buffer_retry_policy_parameter",
        "max_daily_turnover_parameter",
        "max_order_notional_parameter",
        "max_retry_exposure_parameter",
        "max_slippage_parameter",
        "registry_semantics",
        "database_snapshot_consistency",
    ),
    "eod_state_pair": (
        "latest_state",
        "latest_date_row_count",
        "previous_state",
        "previous_date_row_count",
    ),
    "order_mode_ownership_quantity": (
        "operating_mode_db",
        "effective_mode",
        "effective_mode_source",
        "effective_mode_verification",
        "execution_enabled",
        "kill_switch_db",
        "submission_guard_derivation",
        "latest_job",
        "open_order_count",
        "ownership_scope_verification",
        "strategy_off_order_intent_policy",
        "quantity_policy",
        "risk_limit_application",
        "buy_reference_price_policy",
        "sell_reference_price_policy",
        "order_price_rounding",
        "quantity_rounding",
        "fee_pct",
        "sell_proceeds_haircut",
        "sell_quantity_cap",
        "buy_quantity_cap",
        "cash_source_policy",
        "fx_quantity_policy",
        "sell_priority",
        "buy_priority",
        "unsupported_holding_guard",
        "order_contract_source",
        "order_contract_live_process_environment",
    ),
    "production_provider_contract": (
        "quote_provider",
        "auxiliary_provider",
        "contract_source",
        "provider_network_call",
        "provider_contract_verification",
        "provider_live_process_environment",
        "kis_origin_file_contract",
        "kis_quote_contract",
        "kis_quote_method",
        "kis_quote_path",
        "kis_auth_query",
        "kis_exchange_query",
        "kis_symbol_query",
        "kis_session_scheme_contract",
        "kis_default_header_contract",
        "kis_quote_transaction_prefix",
        "kis_quote_transaction_digits_part_1",
        "kis_quote_transaction_digits_part_2",
        "kis_price_field",
        "kis_status_validation",
        "kis_connect_timeout_binding",
        "kis_response_timeout_binding",
        "yahoo_origin",
        "yahoo_chart_contract",
        "yahoo_chart_method",
        "yahoo_chart_path",
        "yahoo_vix_symbol",
        "yahoo_vix_field",
        "yahoo_vix_null_policy",
        "yahoo_history_symbol",
        "yahoo_history_range",
        "yahoo_history_interval",
        "yahoo_history_field",
        "yahoo_history_null_policy",
        "yahoo_history_selection",
        "yahoo_ma_method",
        "yahoo_price_adjustment",
        "yahoo_connect_timeout_binding",
        "yahoo_response_timeout_binding",
        "yahoo_official_status",
        "corporate_action_contract",
    ),
    "price_market_time_semantics": (
        "strategy_price_kind",
        "market_zone",
        "eod_schedule",
        "market_as_of_date_source",
        "upstream_observed_at",
        "upstream_available_at",
    ),
    "kis_vix_ma200_semantics": (
        "base_upstream_date",
        "base_date_verification",
        "vix_source",
        "vix_observed_at",
        "ma_period",
        "ma_session_membership",
        "ma_price_adjustment",
    ),
    "freshness_retry_deadline": (
        "kis_connect_timeout",
        "kis_request_timeout",
        "yahoo_connect_timeout",
        "yahoo_request_timeout",
        "order_max_attempts",
        "order_retry_wait_ms",
        "input_freshness_enforcement",
        "eod_input_retry_schedule",
        "rebalance_input_retry_schedule",
        "eod_deadline",
        "rebalance_deadline",
    ),
    "operational_comparison_tolerances": (
        "rebalance_tolerance_pct",
        "tick_size",
        "price_rounding",
        "quantity_rounding",
        "buy_priority",
        "sell_priority",
        "operational_comparison_window",
        "operational_field_tolerances",
    ),
}

SUMMARIES: dict[str, str] = {
    "java_code_sha": "운영 JAR 배포 상관관계와 서비스 실행 상태별 검증 근거를 함께 기록했다.",
    "effective_runtime_config": "운영 서비스 상태와 허용 설정 및 설치 계약의 일치 여부를 확인했다.",
    "db_strategy_state": "전략 상태와 DB registry 기록값을 SELECT했으며 runtime effective로 간주하지 않는다.",
    "eod_state_pair": "최신 및 직전 EOD 행을 SELECT했으며 행 부재는 MISSING으로 기록했다.",
    "order_mode_ownership_quantity": "주문 모드와 DB 주문 집계의 안전 경계를 확인했다.",
    "production_provider_contract": "배포 코드의 provider 계약을 외부 호출 없이 확인했다.",
    "price_market_time_semantics": "가격과 시장 시각 의미의 보존 여부를 확인했다.",
    "kis_vix_ma200_semantics": "승인 경로가 보존하지 않는 시장 메타데이터를 미확정으로 기록했다.",
    "freshness_retry_deadline": "주문 재시도 값과 별개로 입력 신선도·재시도·deadline 미구현 상태를 기록했다.",
    "operational_comparison_tolerances": "제품 허용값과 별개로 운영 comparator 미구현 상태를 기록했다.",
    "approval_document_code_sha": "C0-A 승인 문서와 코드 revision의 일치 여부를 확인했다.",
    "c0a_diff_summary": "12개 정제 항목의 코드 소유 C0-A 비교를 실행할 준비가 됐다.",
}

FACT_DISPLAY_NAMES: dict[str, dict[str, str]] = {
    item_id: {name: name.replace("_", " ") for name in names}
    for item_id, names in RAW_FACTS.items()
}

KST_OFFSET = timedelta(hours=9)
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
GIT_HASH_FACT_WIDTH = 5
GIT_HASH_FACT_PARTS = 8


def _decimal(value: str) -> Decimal | None:
    try:
        parsed = Decimal(value)
    except InvalidOperation:
        return None
    return parsed if parsed.is_finite() else None


def _valid_iso_date(value: str) -> bool:
    try:
        return datetime.strptime(value, "%Y-%m-%d").strftime("%Y-%m-%d") == value
    except ValueError:
        return False


def _weights_are_percentages(values: Iterable[str]) -> bool:
    parsed = [_decimal(value) for value in values]
    return (
        all(value is not None and value >= 0 for value in parsed)
        and sum(value for value in parsed if value is not None) == Decimal("100")
    )


def _state_row_semantics(value: str) -> bool:
    if value == "MISSING":
        return True
    fields = value.split(",")
    if len(fields) != 13 or not _valid_iso_date(fields[0]):
        return False
    ath, close, drawdown, maximum = (
        _decimal(fields[index]) for index in range(2, 6)
    )
    if (
        ath is None
        or close is None
        or drawdown is None
        or maximum is None
        or ath <= 0
        or close <= 0
        or close > ath
        or drawdown < 0
        or maximum < drawdown
        or maximum >= 100
        or not _weights_are_percentages(fields[10:13])
    ):
        return False
    expected_drawdown = (
        ((ath - close) / ath) * Decimal("100")
    ).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
    return drawdown == expected_drawdown


def _state_row_matches_c0a(value: str) -> bool:
    """Apply the approved bucket, phase, and weight baseline as a DIFF predicate."""

    if value == "MISSING" or not _state_row_semantics(value):
        return False
    fields = value.split(",")
    drawdown = _decimal(fields[4])
    maximum = _decimal(fields[5])
    assert drawdown is not None and maximum is not None
    expected_bucket = (
        "LESS_THAN_15"
        if drawdown < 15
        else "FROM_15_TO_25"
        if drawdown < 25
        else "FROM_25_TO_35"
        if drawdown < 35
        else "FROM_35_TO_45"
        if drawdown < 45
        else "MORE_THAN_45"
    )
    if fields[7] != expected_bucket:
        return False
    expected_phase = (
        "NORMAL"
        if maximum < 15
        else "RECOVERY"
        if drawdown <= 10
        else "DRAWDOWN"
    )
    if fields[6] != expected_phase:
        return False
    weights = tuple(_decimal(value) for value in fields[10:13])
    approved_drawdown_weights = {
        "FROM_15_TO_25": (Decimal("60"), Decimal("30"), Decimal("10")),
        "FROM_25_TO_35": (Decimal("40"), Decimal("40"), Decimal("20")),
        "FROM_35_TO_45": (Decimal("30"), Decimal("30"), Decimal("40")),
        "MORE_THAN_45": (Decimal("20"), Decimal("20"), Decimal("60")),
    }
    if expected_phase == "NORMAL":
        return weights == (Decimal("100"), Decimal("0"), Decimal("0"))
    if expected_phase == "RECOVERY":
        return weights == (Decimal("70"), Decimal("30"), Decimal("0"))
    if drawdown < 15:
        # The Java state machine carries the previous approved target while it
        # is above recovery (10) but below the first DD bucket (15).
        return weights in {
            (Decimal("100"), Decimal("0"), Decimal("0")),
            (Decimal("70"), Decimal("30"), Decimal("0")),
            *approved_drawdown_weights.values(),
        }
    return weights == approved_drawdown_weights[expected_bucket]


def _raw_value_allowed(item_id: str, name: str, value: str) -> bool:
    """Validate semantics in addition to the exact record-name allowlist."""

    exact_values: dict[tuple[str, str], set[str]] = {
        ("java_code_sha", "service_active_state"): {
            "active",
            "reloading",
            "inactive",
            "failed",
            "activating",
            "deactivating",
            "maintenance",
            "refreshing",
        },
        ("java_code_sha", "runtime_code_basis"): {
            "running process jar predates start",
            "disk jar changed or timestamp ambiguous",
            "service not running",
            "timestamp comparison unverified",
        },
        ("effective_runtime_config", "scheduling_enabled"): {"true", "false"},
        ("effective_runtime_config", "execution_enabled"): {"true", "false"},
        ("effective_runtime_config", "operation_mode"): {"PAPER", "MANUAL_LIVE", "AUTO_LIVE"},
        ("effective_runtime_config", "environment_file_contract"): {"EXPECTED", "UNVERIFIED_OVERRIDE_PRESENT"},
        ("effective_runtime_config", "exec_start_contract"): {"EXPECTED", "UNVERIFIED_OVERRIDE_PRESENT"},
        ("effective_runtime_config", "unit_fragment_contract"): {"EXPECTED", "UNVERIFIED_OVERRIDE_PRESENT"},
        ("effective_runtime_config", "drop_in_contract"): {"EXPECTED", "UNVERIFIED_OVERRIDE_PRESENT"},
        ("effective_runtime_config", "working_directory_contract"): {
            "EXPECTED",
            "UNVERIFIED_OVERRIDE_PRESENT",
        },
        ("effective_runtime_config", "external_config_contract"): {
            "EXPECTED",
            "UNVERIFIED_OVERRIDE_PRESENT",
        },
        ("effective_runtime_config", "file_and_unit_override_status"): {
            "NO_UNVERIFIED_OVERRIDE",
            "UNVERIFIED_OVERRIDE_PRESENT",
        },
        ("effective_runtime_config", "env_override_contract"): {"EXPECTED", "UNVERIFIED_OVERRIDE_PRESENT"},
        ("effective_runtime_config", "decision_config_override_contract"): {
            "EXPECTED",
            "UNVERIFIED_OVERRIDE_PRESENT",
        },
        ("effective_runtime_config", "runtime_config_basis"): {
            "running process matches file timestamps",
            "running process files changed since start",
            "next start configuration only",
            "timestamp comparison unverified",
        },
        ("effective_runtime_config", "live_process_environment_verification"): {
            "UNVERIFIED_BY_APPROVED_ROUTE"
        },
        ("db_strategy_state", "registry_semantics"): {
            "database record not runtime effective"
        },
        ("db_strategy_state", "database_snapshot_consistency"): {
            "double-read-nonatomic"
        },
        ("order_mode_ownership_quantity", "operating_mode_db"): {
            "PAPER",
            "MANUAL_LIVE",
            "AUTO_LIVE",
            "NOT_PERSISTED",
        },
        ("order_mode_ownership_quantity", "effective_mode"): {
            "PAPER",
            "MANUAL_LIVE",
            "AUTO_LIVE",
        },
        ("order_mode_ownership_quantity", "effective_mode_source"): {
            "database record",
            "current environment-file fallback",
        },
        ("order_mode_ownership_quantity", "effective_mode_verification"): {
            "VERIFIED_DATABASE_RECORD",
            "UNVERIFIED_LIVE_PROCESS_ENVIRONMENT",
        },
        ("order_mode_ownership_quantity", "execution_enabled"): {"true", "false"},
        ("order_mode_ownership_quantity", "kill_switch_db"): {
            "ON",
            "OFF",
            "NOT_PERSISTED",
        },
        ("order_mode_ownership_quantity", "submission_guard_derivation"): {
            "BLOCKED_KILL_SWITCH",
            "BLOCKED_PAPER_MODE",
            "BLOCKED_EXECUTION_DISABLED",
            "ALLOWED_BY_LOCAL_GUARD",
        },
        ("order_mode_ownership_quantity", "ownership_scope_verification"): {
            "unverified outside Java host"
        },
        ("order_mode_ownership_quantity", "strategy_off_order_intent_policy"): {
            "zero intents"
        },
        ("order_mode_ownership_quantity", "quantity_policy"): {
            "absolute target notional gap divided by limit price and rounded down"
        },
        ("order_mode_ownership_quantity", "risk_limit_application"): {
            "post-sizing order block"
        },
        ("order_mode_ownership_quantity", "buy_reference_price_policy"): {
            "best ask then last"
        },
        ("order_mode_ownership_quantity", "sell_reference_price_policy"): {
            "best bid then last"
        },
        ("order_mode_ownership_quantity", "order_price_rounding"): {
            "HALF_UP-2-decimals"
        },
        ("order_mode_ownership_quantity", "quantity_rounding"): {
            "DOWN-integer"
        },
        ("order_mode_ownership_quantity", "fee_pct"): {"0.25"},
        ("order_mode_ownership_quantity", "sell_proceeds_haircut"): {"0.995"},
        ("order_mode_ownership_quantity", "sell_quantity_cap"): {
            "integer owned position"
        },
        ("order_mode_ownership_quantity", "buy_quantity_cap"): {
            "remaining USD after fee"
        },
        ("order_mode_ownership_quantity", "cash_source_policy"): {
            "KIS USD orderable cash plus fee-adjusted sell proceeds"
        },
        ("order_mode_ownership_quantity", "fx_quantity_policy"): {
            "FX excluded from order quantity"
        },
        ("order_mode_ownership_quantity", "sell_priority"): {
            "TQQQ-QLD-QQQM"
        },
        ("order_mode_ownership_quantity", "buy_priority"): {
            "QQQM-QLD-TQQQ"
        },
        ("order_mode_ownership_quantity", "unsupported_holding_guard"): {
            "QQQ only"
        },
        ("order_mode_ownership_quantity", "order_contract_source"): {
            "pinned deployed Java revision"
        },
        ("order_mode_ownership_quantity", "order_contract_live_process_environment"): {
            "UNVERIFIED_BY_APPROVED_ROUTE"
        },
        ("production_provider_contract", "quote_provider"): {
            "KIS current-file configuration without a network call"
        },
        ("production_provider_contract", "auxiliary_provider"): {
            "Yahoo configured without a network call"
        },
        ("production_provider_contract", "contract_source"): {
            "pinned deployed source blobs"
        },
        ("production_provider_contract", "provider_network_call"): {"none"},
        ("production_provider_contract", "provider_contract_verification"): {
            "UNVERIFIED_BY_APPROVED_ROUTE"
        },
        ("production_provider_contract", "provider_live_process_environment"): {
            "UNVERIFIED_BY_APPROVED_ROUTE"
        },
        ("production_provider_contract", "kis_origin_file_contract"): {
            "EXPECTED_PRODUCTION",
            "UNEXPECTED",
        },
        ("production_provider_contract", "kis_quote_contract"): {"v1_해외주식-009"},
        ("production_provider_contract", "kis_quote_method"): {"GET"},
        ("production_provider_contract", "kis_quote_path"): {
            "/uapi/overseas-price/v1/quotations/price"
        },
        ("production_provider_contract", "kis_auth_query"): {"AUTH empty"},
        ("production_provider_contract", "kis_exchange_query"): {"EXCD NAS"},
        ("production_provider_contract", "kis_symbol_query"): {
            "SYMB runtime signal symbol"
        },
        ("production_provider_contract", "kis_session_scheme_contract"): {
            "source scheme verified with credential value omitted"
        },
        ("production_provider_contract", "kis_default_header_contract"): {
            "JSON content type and configured application credentials"
        },
        ("production_provider_contract", "kis_quote_transaction_prefix"): {"HHDFS"},
        ("production_provider_contract", "kis_quote_transaction_digits_part_1"): {"0000"},
        ("production_provider_contract", "kis_quote_transaction_digits_part_2"): {"0300"},
        ("production_provider_contract", "kis_price_field"): {"output.base"},
        ("production_provider_contract", "kis_status_validation"): {
            "business result code not enforced before output.base use"
        },
        ("production_provider_contract", "kis_connect_timeout_binding"): {
            "Netty connect timeout from KIS properties"
        },
        ("production_provider_contract", "kis_response_timeout_binding"): {
            "Netty response and blocking timeout from KIS request property"
        },
        ("production_provider_contract", "yahoo_origin"): {
            "https://query1.finance.yahoo.com"
        },
        ("production_provider_contract", "yahoo_chart_contract"): {"v8-chart"},
        ("production_provider_contract", "yahoo_chart_method"): {"GET"},
        ("production_provider_contract", "yahoo_chart_path"): {
            "/v8/finance/chart/{symbol}"
        },
        ("production_provider_contract", "yahoo_vix_symbol"): {"^VIX"},
        ("production_provider_contract", "yahoo_vix_field"): {
            "meta.regularMarketPrice"
        },
        ("production_provider_contract", "yahoo_vix_null_policy"): {
            "empty result on missing or failed response"
        },
        ("production_provider_contract", "yahoo_history_symbol"): {
            "runtime signal symbol default QQQM"
        },
        ("production_provider_contract", "yahoo_history_range"): {"1y"},
        ("production_provider_contract", "yahoo_history_interval"): {"1d"},
        ("production_provider_contract", "yahoo_history_field"): {
            "indicators.quote.close"
        },
        ("production_provider_contract", "yahoo_history_null_policy"): {
            "null closes filtered"
        },
        ("production_provider_contract", "yahoo_history_selection"): {
            "latest requested count"
        },
        ("production_provider_contract", "yahoo_ma_method"): {
            "arithmetic mean scale 4 HALF_UP"
        },
        ("production_provider_contract", "yahoo_price_adjustment"): {
            "unadjusted close field"
        },
        ("production_provider_contract", "yahoo_connect_timeout_binding"): {
            "Netty connect timeout from Yahoo properties"
        },
        ("production_provider_contract", "yahoo_response_timeout_binding"): {
            "Netty response and blocking timeout from Yahoo request property"
        },
        ("production_provider_contract", "yahoo_official_status"): {
            "UNVERIFIED_BY_APPROVED_ROUTE"
        },
        ("production_provider_contract", "corporate_action_contract"): {
            "UNVERIFIED_BY_APPROVED_ROUTE"
        },
        ("price_market_time_semantics", "strategy_price_kind"): {"previous-close"},
        ("price_market_time_semantics", "market_zone"): {"America/New_York"},
        ("price_market_time_semantics", "eod_schedule"): {"16:15-business-days"},
        ("price_market_time_semantics", "market_as_of_date_source"): {
            "scheduler-market-date"
        },
        ("price_market_time_semantics", "upstream_observed_at"): {"NOT_PERSISTED"},
        ("price_market_time_semantics", "upstream_available_at"): {"NOT_PERSISTED"},
        ("kis_vix_ma200_semantics", "base_upstream_date"): {"NOT_PERSISTED"},
        ("kis_vix_ma200_semantics", "base_date_verification"): {
            "UNVERIFIED_BY_APPROVED_ROUTE"
        },
        ("kis_vix_ma200_semantics", "vix_source"): {"Yahoo spot not queried"},
        ("kis_vix_ma200_semantics", "vix_observed_at"): {"NOT_PERSISTED"},
        ("kis_vix_ma200_semantics", "ma_session_membership"): {"NOT_PERSISTED"},
        ("kis_vix_ma200_semantics", "ma_price_adjustment"): {
            "UNVERIFIED_BY_APPROVED_ROUTE"
        },
        ("freshness_retry_deadline", "input_freshness_enforcement"): {
            "NOT_IMPLEMENTED"
        },
        ("freshness_retry_deadline", "eod_input_retry_schedule"): {"NOT_IMPLEMENTED"},
        ("freshness_retry_deadline", "rebalance_input_retry_schedule"): {
            "NOT_IMPLEMENTED"
        },
        ("freshness_retry_deadline", "eod_deadline"): {"NOT_IMPLEMENTED"},
        ("freshness_retry_deadline", "rebalance_deadline"): {"NOT_IMPLEMENTED"},
        ("operational_comparison_tolerances", "price_rounding"): {
            "HALF_UP-2-decimals"
        },
        ("operational_comparison_tolerances", "quantity_rounding"): {"DOWN-integer"},
        ("operational_comparison_tolerances", "buy_priority"): {"QQQM-QLD-TQQQ"},
        ("operational_comparison_tolerances", "sell_priority"): {"TQQQ-QLD-QQQM"},
        ("operational_comparison_tolerances", "operational_comparison_window"): {
            "NOT_IMPLEMENTED"
        },
        ("operational_comparison_tolerances", "operational_field_tolerances"): {
            "NOT_IMPLEMENTED"
        },
    }
    exact = exact_values.get((item_id, name))
    if exact is not None:
        return value in exact

    if name == "runtime_release_sha":
        return HEX40.fullmatch(value) is not None
    if name == "runtime_jar_sha256":
        return HEX64.fullmatch(value) is not None
    if name == "spring_profile":
        return value in {"prod", "default"}
    if name == "server_binding":
        if value == "UNVERIFIED_NON_PROD_PROFILE":
            return True
        binding = re.fullmatch(r"(?:127\.0\.0\.1|localhost):([0-9]{1,5})", value)
        return binding is not None and 1 <= int(binding.group(1)) <= 65535
    if name == "service_sub_state":
        return re.fullmatch(r"[a-z-]{1,32}", value) is not None
    if name == "latest_as_of_date":
        return value == "MISSING" or _valid_iso_date(value)
    if name == "signal_symbol":
        return value == "MISSING" or re.fullmatch(r"[A-Z^]{1,8}", value) is not None
    if name == "strategy_on":
        return value in {"0", "1", "MISSING"}
    if name in {
        "version",
        "open_order_count",
        "latest_date_row_count",
        "previous_date_row_count",
    }:
        return (name == "version" and value == "MISSING") or re.fullmatch(r"[0-9]{1,7}", value) is not None
    if name == "weights":
        if value == "MISSING":
            return True
        if re.fullmatch(
            r"QQQM=-?[0-9]{1,3}(?:\.[0-9]{1,6})?,QLD=-?[0-9]{1,3}(?:\.[0-9]{1,6})?,TQQQ=-?[0-9]{1,3}(?:\.[0-9]{1,6})?",
            value,
        ) is None:
            return False
        return _weights_are_percentages(part.split("=", 1)[1] for part in value.split(","))
    if name.endswith("_parameter"):
        if value == "MISSING":
            return True
        parameter_patterns = {
            "dd_bucket_parameter": r"(?:ADOPTED|PROVISIONAL):[0-9]{1,3} / [0-9]{1,3} / [0-9]{1,3} / [0-9]{1,3}%",
            "recovery_rule_parameter": r"(?:ADOPTED|PROVISIONAL):최대 DD [0-9]{1,3}% 이상 \+ 현재 DD [0-9]{1,3}% 이하",
            "rebalance_tolerance_parameter": r"(?:ADOPTED|PROVISIONAL):[0-9]{1,3}(?:\.[0-9]{1,6})?%",
            "vix_threshold_parameter": r"(?:ADOPTED|PROVISIONAL):[0-9]{1,3}(?:\.[0-9]{1,6})?",
            "ma_200_guard_parameter": r"(?:ADOPTED|PROVISIONAL):[A-Z]{1,8} < MA[0-9]{1,4} 시 공격 버킷 1단계 축소",
            "order_buffer_retry_policy_parameter": (
                r"(?:ADOPTED|PROVISIONAL):초기 BUY [0-9]{1,3} tick / SELL [0-9]{1,3} tick, "
                r"재시도 BUY \+[0-9]{1,3} tick / SELL \+[0-9]{1,3} tick, "
                r"최대 [0-9]{1,3}회, 대기 [0-9]{1,7}ms"
            ),
            "max_daily_turnover_parameter": r"(?:ADOPTED|PROVISIONAL):(?:미정의 \(0으로 비활성\)|[0-9]{1,12}(?:\.[0-9]{1,6})? %)",
            "max_order_notional_parameter": r"(?:ADOPTED|PROVISIONAL):(?:미정의 \(0으로 비활성\)|[0-9]{1,12}(?:\.[0-9]{1,6})? USD)",
            "max_retry_exposure_parameter": r"(?:ADOPTED|PROVISIONAL):(?:미정의 \(0으로 비활성\)|[0-9]{1,12}(?:\.[0-9]{1,6})? USD)",
            "max_slippage_parameter": r"(?:ADOPTED|PROVISIONAL):(?:미정의 \(0으로 비활성\)|[0-9]{1,12}(?:\.[0-9]{1,6})? %)",
        }
        return re.fullmatch(parameter_patterns[name], value) is not None
    if name in {"latest_state", "previous_state"}:
        return (value == "MISSING" or re.fullmatch(
            r"[0-9]{4}-[0-9]{2}-[0-9]{2},[A-Z^]{1,8},"
            r"-?[0-9]{1,7}(?:\.[0-9]{1,6})?,-?[0-9]{1,7}(?:\.[0-9]{1,6})?,"
            r"-?[0-9]{1,3}(?:\.[0-9]{1,6})?,-?[0-9]{1,3}(?:\.[0-9]{1,6})?,"
            r"(?:DRAWDOWN|NORMAL|RECOVERY),"
            r"(?:FROM_15_TO_25|FROM_25_TO_35|FROM_35_TO_45|LESS_THAN_15|MORE_THAN_45),"
            r"[01],[0-9]{1,7},-?[0-9]{1,3}(?:\.[0-9]{1,6})?,-?[0-9]{1,3}(?:\.[0-9]{1,6})?,-?[0-9]{1,3}(?:\.[0-9]{1,6})?",
            value,
        ) is not None) and _state_row_semantics(value)
    if name == "latest_job":
        if value == "none":
            return True
        parts = value.split(",")
        return (
            len(parts) == 2
            and _valid_iso_date(parts[0])
            and parts[1] in {"COMPLETED", "FAILED", "PENDING", "RUNNING"}
        )
    if name in {
        "kis_connect_timeout",
        "kis_request_timeout",
        "yahoo_connect_timeout",
        "yahoo_request_timeout",
    }:
        return re.fullmatch(r"(?:[0-9]{1,4}(?:ms|s|m)|PT[0-9]{1,4}S)", value) is not None
    if name == "order_max_attempts":
        return re.fullmatch(r"[0-9]{1,3}", value) is not None
    if name == "order_retry_wait_ms":
        return re.fullmatch(r"[0-9]{1,7}", value) is not None
    if name in {"ma_period"}:
        return re.fullmatch(r"[0-9]{1,4}", value) is not None
    if name in {"rebalance_tolerance_pct", "tick_size"}:
        return re.fullmatch(r"[0-9]{1,3}(?:\.[0-9]{1,6})?", value) is not None
    return False


def parse_kst(value: str, context: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as error:
        raise C0BError(f"{context}: invalid ISO 8601 timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() != KST_OFFSET:
        raise C0BError(f"{context}: timestamp must use +09:00")
    if not value.endswith("+09:00"):
        raise C0BError(f"{context}: timestamp must end with +09:00")
    return parsed


def _clean_text(value: object, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise C0BError(f"{context}: nonempty text is required")
    if value != value.strip() or len(value) > 500:
        raise C0BError(f"{context}: text shape is invalid")
    if "\\|" in value:
        raise C0BError(f"{context}: escaped pipe is not allowed")
    for character in value:
        if unicodedata.category(character) in {"Cc", "Cf"}:
            raise C0BError(f"{context}: control characters are not allowed")
    if unicodedata.normalize("NFKC", value) != value:
        raise C0BError(f"{context}: compatibility Unicode is not allowed")
    return value


def safe_evidence_text(value: object, context: str) -> str:
    """Apply the repository evidence detector to one human-readable value."""

    cleaned = _clean_text(value, context)
    forbidden_patterns = (
        (
            r"(?i)(?:^|[^A-Za-z0-9])(?:authorization(?:[_ -]?header)?|bearer|"
            r"password(?:[_ -]?hash)?|passwd|pwd|secret|token(?:[_ -]?value)?|"
            r"cookie(?:[_ -]?value)?|app[_ -]?key|app[_ -]?secret|api[_ -]?key|"
            r"private[_ -]?key|client[_ -]?secret|access[_ -]?key[_ -]?id|"
            r"(?:access|refresh|id|auth)[_ -]?token)(?:[^A-Za-z0-9]|$)"
        ),
        r"(?i)(?:^|[^A-Za-z0-9])(?:pwd|pass|password|passwd|secret|token|cookie)\s*[:=]",
        r"(?i)\b[A-Z0-9]+_(?:APP_)?(?:SECRET|TOKEN|PASSWORD|PASSWD|PWD|API_KEY|ACCESS_KEY_ID)\b",
        r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
        r"(?i)\b(?:api|private|client)[_ -]?(?:key|secret)\b",
        r"-----BEGIN [A-Z ]+-----",
        r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}",
        r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b",
        r"(?i)\b[0-9a-f]{24,63}\b",
        r"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])",
    )
    if any(re.search(pattern, cleaned) for pattern in forbidden_patterns):
        raise C0BError(f"{context}: possible credential or identifier")
    if re.search(
        r"(?<![A-Za-z0-9])[A-Za-z0-9]{4}(?:[._:/-][A-Za-z0-9]{4}){4,}(?![A-Za-z0-9])",
        cleaned,
    ):
        raise C0BError(f"{context}: possible segmented credential or identifier")
    numeric_scan = re.sub(r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)", "", cleaned)
    numeric_scan = re.sub(
        r"\b(?:127\.0\.0\.1|localhost):[0-9]{1,5}\b", "", numeric_scan
    )
    numeric_scan = numeric_scan.replace("15 / 25 / 35 / 45%", "")
    if re.search(r"(?<!\d)\d{8,16}(?!\d)", numeric_scan) or re.search(
        r"(?<!\d)\d(?:[ ._:/-]*\d){7,15}(?![ ._:/-]*\d)", numeric_scan
    ):
        raise C0BError(f"{context}: possible unmasked numeric identifier")
    return cleaned


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise C0BError("JSON contains a duplicate key")
        result[key] = value
    return result


def load_json(path: Path, context: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise C0BError(f"{context}: regular file required")
    try:
        payload = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=_strict_object
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise C0BError(f"{context}: valid UTF-8 JSON required") from error
    if not isinstance(payload, dict):
        raise C0BError(f"{context}: JSON object required")
    return payload


def exact_keys(payload: dict[str, object], expected: set[str], context: str) -> None:
    if set(payload) != expected:
        raise C0BError(f"{context}: keys do not match the exact allowlist")


def parse_raw_stream(stream: BinaryIO) -> dict[str, dict[str, str]]:
    """Parse the bounded stdin protocol without ever persisting its raw bytes."""

    total = 0
    records: list[tuple[str, str, str]] = []
    ended = False
    for line_number, raw_line in enumerate(stream, start=1):
        total += len(raw_line)
        if total > 256 * 1024 or len(raw_line) > 2048:
            raise C0BError("raw stream exceeds the bounded protocol")
        try:
            line = raw_line.decode("utf-8").rstrip("\n")
        except UnicodeDecodeError as error:
            raise C0BError("raw stream must be UTF-8") from error
        if line.endswith("\r"):
            line = line[:-1]
        if line_number == 1:
            if line != "C0B_RAW_V1":
                raise C0BError("raw stream header is invalid")
            continue
        if ended:
            raise C0BError("raw stream contains data after its terminator")
        if line == "C0B_RAW_END":
            ended = True
            continue
        columns = line.split("\t")
        if len(columns) != 3:
            raise C0BError("raw stream record shape is invalid")
        item_id, name, value = columns
        if item_id not in RAW_FACTS or name not in RAW_FACTS[item_id]:
            raise C0BError("raw stream contains a non-allowlisted record")
        _clean_text(value, f"raw {item_id}.{name}")
        records.append((item_id, name, value))
    if total == 0:
        raise C0BError("raw stream is empty")
    if not ended:
        raise C0BError("raw stream ended before its terminator")

    expected = [
        (item_id, name)
        for item_id in REMOTE_ITEM_IDS
        for name in RAW_FACTS[item_id]
    ]
    observed = [(item_id, name) for item_id, name, _ in records]
    if observed != expected:
        raise C0BError("raw stream records must be complete, unique, and ordered")

    result: dict[str, dict[str, str]] = {item_id: {} for item_id in REMOTE_ITEM_IDS}
    for item_id, name, value in records:
        if name in {"runtime_release_sha"}:
            if not HEX40.fullmatch(value):
                raise C0BError("runtime release revision is malformed")
        elif name in {"runtime_jar_sha256"}:
            if not HEX64.fullmatch(value):
                raise C0BError("runtime JAR digest is malformed")
        else:
            safe_evidence_text(value, f"raw {item_id}.{name}")
        if not _raw_value_allowed(item_id, name, value):
            raise C0BError(f"raw {item_id}.{name}: value violates the semantic allowlist")
        result[item_id][name] = value
    provider = result["production_provider_contract"]
    transaction_id = (
        provider["kis_quote_transaction_prefix"]
        + provider["kis_quote_transaction_digits_part_1"]
        + provider["kis_quote_transaction_digits_part_2"]
    )
    if transaction_id != "HHDFS00000300":
        raise C0BError("KIS quote transaction contract parts do not reconstruct exactly")
    return result


def load_known_deployments(path: Path) -> list[dict[str, str]]:
    payload = load_json(path, "known deployments")
    exact_keys(payload, {"schema_version", "kind", "deployments"}, "known deployments")
    if payload["schema_version"] != 1 or payload["kind"] != "c0b_known_deployments":
        raise C0BError("known deployments metadata is invalid")
    deployments = payload["deployments"]
    if not isinstance(deployments, list) or not deployments:
        raise C0BError("known deployments must not be empty")
    normalized: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for entry in deployments:
        if not isinstance(entry, dict):
            raise C0BError("known deployment entry must be an object")
        exact_keys(
            entry,
            {
                "git_sha",
                "jar_sha256",
                "run_id",
                "run_head_sha",
                "run_conclusion",
                "workflow_file",
                "run_url",
            },
            "known deployment entry",
        )
        git_sha = entry.get("git_sha")
        jar_sha = entry.get("jar_sha256")
        run_id = entry.get("run_id")
        run_head_sha = entry.get("run_head_sha")
        run_conclusion = entry.get("run_conclusion")
        workflow_file = entry.get("workflow_file")
        run_url = entry.get("run_url")
        if not isinstance(git_sha, str) or not HEX40.fullmatch(git_sha):
            raise C0BError("known deployment git revision is malformed")
        if not isinstance(jar_sha, str) or not HEX64.fullmatch(jar_sha):
            raise C0BError("known deployment JAR digest is malformed")
        if type(run_id) is not int or run_id <= 0:
            raise C0BError("known deployment run ID is malformed")
        if run_head_sha != git_sha or run_conclusion != "success":
            raise C0BError("known deployment run result does not match its Git revision")
        if workflow_file != ".github/workflows/deploy-prod.yml":
            raise C0BError("known deployment workflow provenance is malformed")
        if run_url != (
            f"https://github.com/ksi2564/systematic-trading/actions/runs/{run_id}"
        ):
            raise C0BError("known deployment run URL is malformed")
        pair = (git_sha, jar_sha)
        if pair in seen:
            raise C0BError("known deployment entries must be unique")
        seen.add(pair)
        normalized.append(
            {
                "git_sha": git_sha,
                "jar_sha256": jar_sha,
                "run_id": str(run_id),
                "run_head_sha": run_head_sha,
                "run_conclusion": run_conclusion,
                "workflow_file": workflow_file,
                "run_url": run_url,
            }
        )
    expected = [
        {
            "git_sha": PINNED_RUNTIME_SHA,
            "jar_sha256": PINNED_RUNTIME_JAR_SHA256,
            "run_id": PINNED_DEPLOY_RUN_ID,
            "run_head_sha": PINNED_RUNTIME_SHA,
            "run_conclusion": "success",
            "workflow_file": ".github/workflows/deploy-prod.yml",
            "run_url": (
                "https://github.com/ksi2564/systematic-trading/actions/runs/"
                + PINNED_DEPLOY_RUN_ID
            ),
        }
    ]
    if normalized != expected:
        raise C0BError("known deployments do not match the code-owned production pin")
    return normalized


def resolve_deployment(
    raw: dict[str, dict[str, str]], deployments: Iterable[dict[str, str]]
) -> str:
    service_state = raw["java_code_sha"]["service_active_state"]
    runtime_basis = raw["java_code_sha"]["runtime_code_basis"]
    if service_state in {"active", "reloading"}:
        if runtime_basis != "running process jar predates start":
            raise C0BError("running Java code cannot be proven from the disk JAR")
    elif runtime_basis != "service not running":
        raise C0BError("non-running service has inconsistent runtime code basis")
    release_sha = raw["java_code_sha"]["runtime_release_sha"]
    jar_sha = raw["java_code_sha"]["runtime_jar_sha256"]
    matches = [
        entry
        for entry in deployments
        if entry["git_sha"] == release_sha and entry["jar_sha256"] == jar_sha
    ]
    if len(matches) != 1:
        raise C0BError("runtime code cannot be resolved to one known deployment")
    return matches[0]["git_sha"]


def verify_local_git_commit(repository_root: Path, git_sha: str) -> None:
    if repository_root.is_symlink() or not repository_root.is_dir():
        raise C0BError("repository root must be a regular directory")
    result = subprocess.run(
        [
            "git",
            "--no-replace-objects",
            "-C",
            str(repository_root),
            "cat-file",
            "-e",
            f"{git_sha}^{{commit}}",
        ],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        raise C0BError("resolved runtime revision is not a local Git commit object")


def verify_pinned_source_blobs(repository_root: Path, git_sha: str) -> None:
    if git_sha != PINNED_RUNTIME_SHA:
        raise C0BError("provider source revision is not the pinned production runtime")
    for source_path, blob_sha in PINNED_SOURCE_BLOBS.items():
        result = subprocess.run(
            [
                "git",
                "--no-replace-objects",
                "-C",
                str(repository_root),
                "ls-tree",
                "-z",
                git_sha,
                "--",
                source_path,
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        expected = f"100644 blob {blob_sha}\t{source_path}\0".encode()
        if result.returncode != 0 or result.stdout != expected:
            raise C0BError("pinned provider source blob verification failed")


def approval_metadata(decision_path: Path) -> tuple[str, str, datetime, datetime]:
    """Read the common C0-A SHA and collection approval time from the record."""

    if decision_path.is_symlink() or not decision_path.is_file():
        raise C0BError("C0 decision record must be a regular file")
    text = decision_path.read_text(encoding="utf-8")
    rows = re.findall(
        r"(?m)^\| (D-[0-9]{2}) \| .*? \| 조건부 승인 \| ([^|]+) \| "
        r"([^|]+) \| 문서=([0-9a-f]{40}); 코드=([0-9a-f]{40}) \|$",
        text,
    )
    if [row[0] for row in rows] != [f"D-{number:02d}" for number in range(1, 11)]:
        raise C0BError("C0-A approval record is incomplete or unordered")
    if any(row[1].strip() != "Inys" for row in rows):
        raise C0BError("C0-A approval owner must be Inys")
    sha_pairs = {(row[3], row[4]) for row in rows}
    if len(sha_pairs) != 1:
        raise C0BError("C0-A approval revisions are inconsistent")
    approval_times = [parse_kst(row[2].strip(), f"{row[0]} approval") for row in rows]
    scope = (
        "대상=12개; 접근방법=GitHub Actions PROD SSH로 운영 호스트 shell 조회·DB SELECT; "
        "권한=읽기 전용; 정제=필수; 저장위치=docs/v2-cutover/evidence/c0b/; "
        "원문저장=금지; 보존=Git 이력"
    )
    collection_pattern = re.compile(
        rf"(?m)^\| C0-B 읽기 전용 수집 승인 \| {re.escape(scope)} \| - \| "
        r"수집 승인 \| (Inys) \| ([^|]+) \|$"
    )
    collection_matches = collection_pattern.findall(text)
    if len(collection_matches) != 1:
        raise C0BError("C0-B collection approval is missing or malformed")
    collection_time = parse_kst(collection_matches[0][1].strip(), "collection approval")
    latest_c0a = max(approval_times)
    if collection_time < latest_c0a:
        raise C0BError("collection approval predates C0-A approval")
    document_sha, code_sha = next(iter(sha_pairs))
    if (document_sha, code_sha) != (
        APPROVED_C0A_DOCUMENT_SHA,
        APPROVED_C0A_CODE_SHA,
    ):
        raise C0BError("C0-A approval revisions do not match the code-owned baseline")
    return document_sha, code_sha, latest_c0a, collection_time


def _split_hash_facts(name: str, value: str, width: int) -> list[dict[str, str]]:
    parts = [value[index : index + width] for index in range(0, len(value), width)]
    return [
        {"name": f"{name} part {index + 1}", "value": part}
        for index, part in enumerate(parts)
    ]


def _workflow_run_facts(run_id: str, run_attempt: str) -> list[dict[str, str]]:
    if re.fullmatch(r"[1-9][0-9]{0,19}", run_id) is None:
        raise C0BError("workflow run ID is malformed")
    if re.fullmatch(r"[1-9][0-9]{0,3}", run_attempt) is None:
        raise C0BError("workflow run attempt is malformed")
    padded = run_id.zfill(20)
    return [
        {"name": "collection workflow run id length", "value": str(len(run_id))},
        *[
            {
                "name": f"collection workflow run id part {index + 1}",
                "value": padded[index * 4 : (index + 1) * 4],
            }
            for index in range(5)
        ],
        {"name": "collection workflow run attempt", "value": run_attempt},
    ]


def make_capture_payloads(
    raw: dict[str, dict[str, str]],
    *,
    captured_at: str,
    collector: str,
    workflow_sha: str,
    runtime_git_sha: str,
    approval_document_sha: str,
    approval_code_sha: str,
    workflow_run_id: str,
    workflow_run_attempt: str,
) -> dict[str, dict[str, object]]:
    parse_kst(captured_at, "capture time")
    safe_evidence_text(collector, "collector")
    if not all(
        HEX40.fullmatch(value)
        for value in (
            workflow_sha,
            runtime_git_sha,
            approval_document_sha,
            approval_code_sha,
        )
    ):
        raise C0BError("workflow, runtime, or approval revision is malformed")
    if runtime_git_sha != raw["java_code_sha"]["runtime_release_sha"]:
        raise C0BError("resolved runtime revision differs from the raw release revision")

    payloads: dict[str, dict[str, object]] = {}
    for item_id in REMOTE_ITEM_IDS:
        facts: list[dict[str, str]] = []
        for name in RAW_FACTS[item_id]:
            value = raw[item_id][name]
            if name == "runtime_release_sha":
                facts.extend(
                    _split_hash_facts(
                        "runtime git revision", value, GIT_HASH_FACT_WIDTH
                    )
                )
            elif name == "runtime_jar_sha256":
                facts.extend(_split_hash_facts("runtime JAR digest", value, 8))
            else:
                facts.append(
                    {"name": FACT_DISPLAY_NAMES[item_id][name], "value": value}
                )
        if item_id == "java_code_sha":
            facts.append(
                {"name": "deployment correlation", "value": "matched known production deployment"}
            )
        elif item_id == "order_mode_ownership_quantity":
            facts.extend(
                _split_hash_facts(
                    "order contract runtime revision",
                    runtime_git_sha,
                    GIT_HASH_FACT_WIDTH,
                )
            )
        elif item_id == "production_provider_contract":
            facts.extend(
                _split_hash_facts(
                    "provider contract runtime revision",
                    runtime_git_sha,
                    GIT_HASH_FACT_WIDTH,
                )
            )
        payloads[item_id] = capture_payload(
            item_id, captured_at, collector, SUMMARIES[item_id], facts
        )

    payloads["approval_document_code_sha"] = capture_payload(
        "approval_document_code_sha",
        captured_at,
        collector,
        SUMMARIES["approval_document_code_sha"],
        [
            *_split_hash_facts(
                "approval document revision",
                approval_document_sha,
                GIT_HASH_FACT_WIDTH,
            ),
            *_split_hash_facts(
                "approval code revision", approval_code_sha, GIT_HASH_FACT_WIDTH
            ),
            *_split_hash_facts(
                "collection workflow revision", workflow_sha, GIT_HASH_FACT_WIDTH
            ),
            *_workflow_run_facts(workflow_run_id, workflow_run_attempt),
            {"name": "approval revision source", "value": "C0-A decision record"},
            {
                "name": "approval revision consistency",
                "value": "all ten decisions share one document and code revision",
            },
            {
                "name": "approval provenance validation",
                "value": "matched local approved commits and workflow commit",
            },
        ],
    )
    payloads["c0a_diff_summary"] = capture_payload(
        "c0a_diff_summary",
        captured_at,
        collector,
        SUMMARIES["c0a_diff_summary"],
        [
            {"name": "comparison scope", "value": "twelve sanitized items"},
            {
                "name": "comparison state",
                "value": "code owned comparison pending in collection workflow",
            },
        ],
    )
    return payloads


def capture_payload(
    item_id: str,
    captured_at: str,
    collector: str,
    summary: str,
    facts: list[dict[str, str]],
) -> dict[str, object]:
    if item_id not in ITEM_IDS:
        raise C0BError("capture item ID is not allowlisted")
    parse_kst(captured_at, f"{item_id} capture time")
    safe_evidence_text(collector, f"{item_id} collector")
    safe_evidence_text(summary, f"{item_id} summary")
    if not 1 <= len(facts) <= 50:
        raise C0BError(f"{item_id}: fact count is outside the contract")
    normalized_facts: list[dict[str, str]] = []
    for index, fact in enumerate(facts):
        if set(fact) != {"name", "value"}:
            raise C0BError(f"{item_id}: fact keys are not exact")
        normalized_facts.append(
            {
                "name": safe_evidence_text(fact["name"], f"{item_id} fact {index} name"),
                "value": safe_evidence_text(fact["value"], f"{item_id} fact {index} value"),
            }
        )
    return {
        "schema_version": 1,
        "kind": "c0b_capture",
        "id": item_id,
        "captured_at": captured_at,
        "collector": collector,
        "sanitized": True,
        "data": {"summary": summary, "facts": normalized_facts},
    }


def _expected_fact_names(item_id: str, final_bundle: bool) -> list[str]:
    if item_id in REMOTE_ITEM_IDS:
        names: list[str] = []
        for raw_name in RAW_FACTS[item_id]:
            if raw_name == "runtime_release_sha":
                names.extend(
                    [
                        f"runtime git revision part {index}"
                        for index in range(1, GIT_HASH_FACT_PARTS + 1)
                    ]
                )
            elif raw_name == "runtime_jar_sha256":
                names.extend([f"runtime JAR digest part {index}" for index in range(1, 9)])
            else:
                names.append(FACT_DISPLAY_NAMES[item_id][raw_name])
        if item_id == "java_code_sha":
            names.append("deployment correlation")
        elif item_id == "order_mode_ownership_quantity":
            names.extend(
                [
                    f"order contract runtime revision part {index}"
                    for index in range(1, GIT_HASH_FACT_PARTS + 1)
                ]
            )
        elif item_id == "production_provider_contract":
            names.extend(
                [
                    f"provider contract runtime revision part {index}"
                    for index in range(1, GIT_HASH_FACT_PARTS + 1)
                ]
            )
        return names
    if item_id == "approval_document_code_sha":
        return [
            *[
                f"approval document revision part {index}"
                for index in range(1, GIT_HASH_FACT_PARTS + 1)
            ],
            *[
                f"approval code revision part {index}"
                for index in range(1, GIT_HASH_FACT_PARTS + 1)
            ],
            *[
                f"collection workflow revision part {index}"
                for index in range(1, GIT_HASH_FACT_PARTS + 1)
            ],
            "collection workflow run id length",
            "collection workflow run id part 1",
            "collection workflow run id part 2",
            "collection workflow run id part 3",
            "collection workflow run id part 4",
            "collection workflow run id part 5",
            "collection workflow run attempt",
            "approval revision source",
            "approval revision consistency",
            "approval provenance validation",
        ]
    if final_bundle:
        return [
            "substantive aggregate result",
            "different substantive item count",
            "matching substantive item count",
            "next gate",
        ]
    return ["comparison scope", "comparison state"]


def _remote_values_from_capture(
    payload: dict[str, object], item_id: str
) -> dict[str, str]:
    data = payload["data"]
    assert isinstance(data, dict)
    facts = data["facts"]
    assert isinstance(facts, list)
    values_by_name = {
        str(fact["name"]): str(fact["value"])
        for fact in facts
        if isinstance(fact, dict)
    }
    result: dict[str, str] = {}
    for raw_name in RAW_FACTS[item_id]:
        if raw_name == "runtime_release_sha":
            value = "".join(
                values_by_name[f"runtime git revision part {index}"]
                for index in range(1, GIT_HASH_FACT_PARTS + 1)
            )
        elif raw_name == "runtime_jar_sha256":
            value = "".join(
                values_by_name[f"runtime JAR digest part {index}"]
                for index in range(1, 9)
            )
        else:
            value = values_by_name[FACT_DISPLAY_NAMES[item_id][raw_name]]
        if not _raw_value_allowed(item_id, raw_name, value):
            raise C0BError(
                f"capture {item_id}.{raw_name}: value violates the semantic allowlist"
            )
        result[raw_name] = value
    if item_id == "production_provider_contract":
        reconstructed = (
            result["kis_quote_transaction_prefix"]
            + result["kis_quote_transaction_digits_part_1"]
            + result["kis_quote_transaction_digits_part_2"]
        )
        if reconstructed != "HHDFS00000300":
            raise C0BError("KIS quote transaction capture parts do not reconstruct exactly")
    return result


def validate_capture(
    payload: dict[str, object], expected_id: str, *, final_bundle: bool = False
) -> datetime:
    exact_keys(
        payload,
        {"schema_version", "kind", "id", "captured_at", "collector", "sanitized", "data"},
        f"capture {expected_id}",
    )
    if payload["schema_version"] != 1 or payload["kind"] != "c0b_capture":
        raise C0BError(f"capture {expected_id}: schema marker is invalid")
    if payload["id"] != expected_id or payload["sanitized"] is not True:
        raise C0BError(f"capture {expected_id}: identity or sanitized marker is invalid")
    captured_at = payload.get("captured_at")
    if not isinstance(captured_at, str):
        raise C0BError(f"capture {expected_id}: captured_at must be text")
    parsed = parse_kst(captured_at, f"capture {expected_id}")
    collector = safe_evidence_text(
        payload.get("collector"), f"capture {expected_id} collector"
    )
    if collector != EXPECTED_COLLECTOR:
        raise C0BError(f"capture {expected_id}: collector is not the approved route")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise C0BError(f"capture {expected_id}: data object required")
    exact_keys(data, {"summary", "facts"}, f"capture {expected_id} data")
    summary = safe_evidence_text(data.get("summary"), f"capture {expected_id} summary")
    expected_summary = (
        FINAL_DIFF_SUMMARY
        if expected_id == "c0a_diff_summary" and final_bundle
        else SUMMARIES[expected_id]
    )
    if summary != expected_summary:
        raise C0BError(f"capture {expected_id}: summary is not the contract value")
    facts = data.get("facts")
    if not isinstance(facts, list) or not 1 <= len(facts) <= 50:
        raise C0BError(f"capture {expected_id}: invalid fact count")
    observed_names: list[str] = []
    observed_values: list[str] = []
    for index, fact in enumerate(facts):
        if not isinstance(fact, dict):
            raise C0BError(f"capture {expected_id}: fact must be an object")
        exact_keys(fact, {"name", "value"}, f"capture {expected_id} fact")
        observed_names.append(
            safe_evidence_text(fact.get("name"), f"capture {expected_id} fact {index} name")
        )
        observed_values.append(
            safe_evidence_text(fact.get("value"), f"capture {expected_id} fact {index} value")
        )
    if observed_names != _expected_fact_names(expected_id, final_bundle):
        raise C0BError(f"capture {expected_id}: fact names must be exact and ordered")

    if expected_id in REMOTE_ITEM_IDS:
        _remote_values_from_capture(payload, expected_id)
        if expected_id == "java_code_sha" and observed_values[-1] != (
            "matched known production deployment"
        ):
            raise C0BError("Java capture deployment correlation marker is invalid")
    elif expected_id == "approval_document_code_sha":
        revision_fact_count = GIT_HASH_FACT_PARTS * 3
        if not all(
            HEX40.fullmatch(
                "".join(
                    observed_values[index : index + GIT_HASH_FACT_PARTS]
                )
            )
            for index in (
                0,
                GIT_HASH_FACT_PARTS,
                GIT_HASH_FACT_PARTS * 2,
            )
        ):
            raise C0BError("approval revision capture parts are malformed")
        if re.fullmatch(r"[1-9]|1[0-9]|20", observed_values[revision_fact_count]) is None or not all(
            re.fullmatch(r"[0-9]{4}", value)
            for value in observed_values[
                revision_fact_count + 1 : revision_fact_count + 6
            ]
        ):
            raise C0BError("workflow run ID capture parts are malformed")
        run_length = int(observed_values[revision_fact_count])
        padded_run_id = "".join(
            observed_values[revision_fact_count + 1 : revision_fact_count + 6]
        )
        run_id = padded_run_id[-run_length:]
        if (
            padded_run_id[:-run_length] != "0" * (20 - run_length)
            or re.fullmatch(r"[1-9][0-9]{0,19}", run_id) is None
            or re.fullmatch(
                r"[1-9][0-9]{0,3}", observed_values[revision_fact_count + 6]
            )
            is None
        ):
            raise C0BError("workflow run provenance is malformed")
        if observed_values[revision_fact_count + 7 :] != [
            "C0-A decision record",
            "all ten decisions share one document and code revision",
            "matched local approved commits and workflow commit",
        ]:
            raise C0BError("approval revision capture facts are invalid")
    elif final_bundle:
        if observed_values[0] not in {"NO_DIFF", "DIFF"}:
            raise C0BError("final diff summary aggregate result is invalid")
        if not all(re.fullmatch(r"[0-9]{1,2}", value) for value in observed_values[1:3]):
            raise C0BError("final diff summary counts are invalid")
        if int(observed_values[1]) + int(observed_values[2]) != 11:
            raise C0BError("final diff summary counts must cover eleven substantive items")
        if observed_values[3] != "C0-B result reapproval required before C2 development":
            raise C0BError("final diff summary gate marker is invalid")
    elif observed_values != [
        "twelve sanitized items",
        "code owned comparison pending in collection workflow",
    ]:
        raise C0BError("initial diff summary capture facts are invalid")
    return parsed


def _validate_deployment_capture(
    payloads: dict[str, dict[str, object]],
    known_deployments_path: Path,
    repository_root: Path,
) -> None:
    java_raw = _remote_values_from_capture(payloads["java_code_sha"], "java_code_sha")
    deployments = load_known_deployments(known_deployments_path)
    runtime_sha = resolve_deployment({"java_code_sha": java_raw}, deployments)
    verify_local_git_commit(repository_root, runtime_sha)
    verify_pinned_source_blobs(repository_root, runtime_sha)


def _approval_provenance_from_capture(
    payload: dict[str, object],
) -> tuple[str, str, str, str, str]:
    data = payload.get("data")
    if not isinstance(data, dict) or not isinstance(data.get("facts"), list):
        raise C0BError("approval provenance capture is malformed")
    facts = data["facts"]
    values = [str(fact["value"]) for fact in facts if isinstance(fact, dict)]
    revision_fact_count = GIT_HASH_FACT_PARTS * 3
    expected_count = revision_fact_count + 10
    if len(values) != expected_count:
        raise C0BError("approval provenance capture fact count is invalid")
    document_sha = "".join(values[0:GIT_HASH_FACT_PARTS])
    code_start = GIT_HASH_FACT_PARTS
    workflow_start = GIT_HASH_FACT_PARTS * 2
    run_start = revision_fact_count
    code_sha = "".join(values[code_start:workflow_start])
    workflow_sha = "".join(values[workflow_start:run_start])
    run_length = int(values[run_start])
    padded_run_id = "".join(values[run_start + 1 : run_start + 6])
    return (
        document_sha,
        code_sha,
        workflow_sha,
        padded_run_id[-run_length:],
        values[run_start + 6],
    )


def _validate_approval_capture(
    payloads: dict[str, dict[str, object]],
    decision_file: Path,
    repository_root: Path,
    *,
    expected_workflow_sha: str | None = None,
    expected_workflow_run_id: str | None = None,
    expected_workflow_run_attempt: str | None = None,
) -> None:
    document_sha, code_sha, workflow_sha, run_id, run_attempt = _approval_provenance_from_capture(
        payloads["approval_document_code_sha"]
    )
    approved_document_sha, approved_code_sha, _, _ = approval_metadata(decision_file)
    if (document_sha, code_sha) != (approved_document_sha, approved_code_sha):
        raise C0BError("approval capture revisions do not match the decision record")
    for revision in {document_sha, code_sha, workflow_sha}:
        verify_local_git_commit(repository_root, revision)
    expected_context = (
        expected_workflow_sha,
        expected_workflow_run_id,
        expected_workflow_run_attempt,
    )
    if any(value is not None for value in expected_context):
        if not all(isinstance(value, str) for value in expected_context):
            raise C0BError("expected workflow context must be supplied as one complete set")
        assert expected_workflow_sha is not None
        assert expected_workflow_run_id is not None
        assert expected_workflow_run_attempt is not None
        if HEX40.fullmatch(expected_workflow_sha) is None:
            raise C0BError("expected workflow revision is malformed")
        if re.fullmatch(r"[1-9][0-9]{0,19}", expected_workflow_run_id) is None:
            raise C0BError("expected workflow run ID is malformed")
        if re.fullmatch(r"[1-9][0-9]{0,3}", expected_workflow_run_attempt) is None:
            raise C0BError("expected workflow run attempt is malformed")
        if (workflow_sha, run_id, run_attempt) != expected_context:
            raise C0BError("approval capture does not match the expected workflow context")


def _validate_cross_capture_coherence(
    payloads: dict[str, dict[str, object]],
) -> None:
    java = _remote_values_from_capture(payloads["java_code_sha"], "java_code_sha")
    db = _remote_values_from_capture(payloads["db_strategy_state"], "db_strategy_state")
    eod = _remote_values_from_capture(payloads["eod_state_pair"], "eod_state_pair")
    latest = eod["latest_state"]
    if (latest == "MISSING") != (eod["latest_date_row_count"] == "0"):
        raise C0BError("latest EOD row count is inconsistent")
    if (eod["previous_state"] == "MISSING") != (
        eod["previous_date_row_count"] == "0"
    ):
        raise C0BError("previous EOD row count is inconsistent")
    if db["latest_as_of_date"] == "MISSING":
        if latest != "MISSING":
            raise C0BError("DB latest row and EOD pair disagree")
    else:
        if latest == "MISSING":
            raise C0BError("DB latest row and EOD pair disagree")
        fields = latest.split(",")
        if len(fields) != 13:
            raise C0BError("latest EOD state shape is inconsistent")
        pair_weights = f"QQQM={fields[10]},QLD={fields[11]},TQQQ={fields[12]}"
        if [
            db["latest_as_of_date"],
            db["signal_symbol"],
            db["strategy_on"],
            db["version"],
            db["weights"],
        ] != [fields[0], fields[1], fields[8], fields[9], pair_weights]:
            raise C0BError("DB latest row and EOD pair fields are inconsistent")
    if eod["previous_state"] != "MISSING":
        if latest == "MISSING" or not (
            eod["previous_state"].split(",", 1)[0]
            < latest.split(",", 1)[0]
        ):
            raise C0BError("EOD pair must contain two distinct descending market dates")

    config = _remote_values_from_capture(
        payloads["effective_runtime_config"], "effective_runtime_config"
    )
    order = _remote_values_from_capture(
        payloads["order_mode_ownership_quantity"],
        "order_mode_ownership_quantity",
    )
    order_data = payloads["order_mode_ownership_quantity"].get("data")
    if not isinstance(order_data, dict) or not isinstance(order_data.get("facts"), list):
        raise C0BError("order contract provenance facts are malformed")
    order_facts = {
        str(fact.get("name")): str(fact.get("value"))
        for fact in order_data["facts"]
        if isinstance(fact, dict)
    }
    order_revision = "".join(
        order_facts.get(f"order contract runtime revision part {index}", "")
        for index in range(1, GIT_HASH_FACT_PARTS + 1)
    )
    if order_revision != java["runtime_release_sha"]:
        raise C0BError("order contract facts are not bound to the runtime revision")
    provider_data = payloads["production_provider_contract"].get("data")
    if not isinstance(provider_data, dict) or not isinstance(
        provider_data.get("facts"), list
    ):
        raise C0BError("provider contract provenance facts are malformed")
    provider_facts = {
        str(fact.get("name")): str(fact.get("value"))
        for fact in provider_data["facts"]
        if isinstance(fact, dict)
    }
    provider_revision = "".join(
        provider_facts.get(f"provider contract runtime revision part {index}", "")
        for index in range(1, GIT_HASH_FACT_PARTS + 1)
    )
    if provider_revision != java["runtime_release_sha"]:
        raise C0BError("provider contract facts are not bound to the runtime revision")
    if order["execution_enabled"] != config["execution_enabled"]:
        raise C0BError("effective execution setting differs between captures")
    if order["effective_mode_source"] == "current environment-file fallback":
        if order["operating_mode_db"] != "NOT_PERSISTED" or order[
            "effective_mode"
        ] != config["operation_mode"]:
            raise C0BError("configuration fallback mode is inconsistent")
        if order["effective_mode_verification"] != (
            "UNVERIFIED_LIVE_PROCESS_ENVIRONMENT"
        ) or config["live_process_environment_verification"] != (
            "UNVERIFIED_BY_APPROVED_ROUTE"
        ):
            raise C0BError("configuration fallback verification is inconsistent")
    elif order["operating_mode_db"] == "NOT_PERSISTED" or order[
        "effective_mode"
    ] != order["operating_mode_db"]:
        raise C0BError("database effective mode is inconsistent")
    elif order["effective_mode_verification"] != "VERIFIED_DATABASE_RECORD":
        raise C0BError("database effective mode verification is inconsistent")


def validate_capture_directory(
    path: Path,
    expected_time: str | None = None,
    *,
    known_deployments_path: Path = DEFAULT_KNOWN_DEPLOYMENTS,
    repository_root: Path = DEFAULT_REPOSITORY_ROOT,
    decision_file: Path = DEFAULT_DECISION_FILE,
    final_bundle: bool = False,
    expected_workflow_sha: str | None = None,
    expected_workflow_run_id: str | None = None,
    expected_workflow_run_attempt: str | None = None,
) -> dict[str, dict[str, object]]:
    if path.is_symlink() or not path.is_dir():
        raise C0BError("capture directory must be a regular directory")
    entries = sorted(path.iterdir(), key=lambda value: value.name)
    if [entry.name for entry in entries] != ["items"] or entries[0].is_symlink():
        raise C0BError("capture directory must contain only items")
    item_dir = entries[0]
    if not item_dir.is_dir():
        raise C0BError("items must be a directory")
    expected_names = sorted(f"{item_id}.json" for item_id in ITEM_IDS)
    files = sorted(item_dir.iterdir(), key=lambda value: value.name)
    if [entry.name for entry in files] != expected_names:
        raise C0BError("capture directory must contain exactly twelve named JSON files")
    payloads: dict[str, dict[str, object]] = {}
    for item_id in ITEM_IDS:
        item_path = item_dir / f"{item_id}.json"
        mode = item_path.lstat().st_mode
        if item_path.is_symlink() or not stat.S_ISREG(mode):
            raise C0BError("capture artifacts must be regular files")
        payload = load_json(item_path, f"capture {item_id}")
        parsed_time = validate_capture(payload, item_id, final_bundle=final_bundle)
        if expected_time is not None and parsed_time != parse_kst(expected_time, "expected capture time"):
            raise C0BError(f"capture {item_id}: time does not match expected value")
        payloads[item_id] = payload
    _validate_cross_capture_coherence(payloads)
    _validate_deployment_capture(payloads, known_deployments_path, repository_root)
    _validate_approval_capture(
        payloads,
        decision_file,
        repository_root,
        expected_workflow_sha=expected_workflow_sha,
        expected_workflow_run_id=expected_workflow_run_id,
        expected_workflow_run_attempt=expected_workflow_run_attempt,
    )
    return payloads


def canonical_bytes(payload: dict[str, object]) -> bytes:
    return (json.dumps(payload, ensure_ascii=False, sort_keys=False, separators=(",", ":")) + "\n").encode("utf-8")


def write_capture_directory_atomic(
    output_dir: Path,
    payloads: dict[str, dict[str, object]],
    *,
    known_deployments_path: Path = DEFAULT_KNOWN_DEPLOYMENTS,
    repository_root: Path = DEFAULT_REPOSITORY_ROOT,
    decision_file: Path = DEFAULT_DECISION_FILE,
) -> None:
    if output_dir.exists() or output_dir.is_symlink():
        raise C0BError("output directory must not already exist")
    parent = output_dir.parent
    if parent.is_symlink() or not parent.is_dir():
        raise C0BError("output parent must be an existing regular directory")
    temporary = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}.tmp-", dir=parent))
    try:
        os.chmod(temporary, 0o700)
        item_dir = temporary / "items"
        item_dir.mkdir(mode=0o700)
        if tuple(payloads) != ITEM_IDS:
            raise C0BError("capture payloads must contain the twelve ordered items")
        for item_id in ITEM_IDS:
            validate_capture(payloads[item_id], item_id)
            item_path = item_dir / f"{item_id}.json"
            with item_path.open("xb") as output:
                output.write(canonical_bytes(payloads[item_id]))
            os.chmod(item_path, 0o600)
        validate_capture_directory(
            temporary,
            known_deployments_path=known_deployments_path,
            repository_root=repository_root,
            decision_file=decision_file,
        )
        os.replace(temporary, output_dir)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


COMPARISON_SUMMARIES: dict[str, dict[bool, str]] = {
    "java_code_sha": {
        True: "Known production deployment and active running JAR evidence match the runtime predicate.",
        False: "The service is not active with an unambiguous running JAR basis.",
    },
    "effective_runtime_config": {
        True: "Effective production safety settings and installed service contracts match the approved baseline.",
        False: "The live JVM environment is unverified, or a file, unit, or setting contract differs from the baseline.",
    },
    "db_strategy_state": {
        True: "The complete DB strategy record matches the code-owned C0-A parameter baseline.",
        False: "The DB read is non-atomic, duplicated, missing, or differs from the code-owned C0-A baseline.",
    },
    "eod_state_pair": {
        True: "Latest and immediately previous QQQM EOD states are present and chronologically coherent.",
        False: "The EOD pair is non-atomic, duplicated, missing, or not chronologically coherent.",
    },
    "order_mode_ownership_quantity": {
        True: "Paper-mode blocking, D-05 quantity rules, arbitrary unsupported-holding protection, and exclusive ownership match the safety baseline.",
        False: "Live order settings, exclusive ownership, or arbitrary unsupported-holding protection are unverified, or another D-05 rule differs.",
    },
    "production_provider_contract": {
        True: "The no-call production provider contracts match the code-owned baseline.",
        False: "Official completeness and corporate-action semantics or the live provider environment remain unverified.",
    },
    "price_market_time_semantics": {
        True: "Price, market-date, observed-time, and available-time semantics match C0-A.",
        False: "Legacy price or timestamp semantics differ from the C0-A confirmed raw-close contract.",
    },
    "kis_vix_ma200_semantics": {
        True: "KIS base, VIX observation, and MA200 session semantics are fully verified.",
        False: "KIS base, VIX observation, or MA200 session semantics remain unverified or unpersisted.",
    },
    "freshness_retry_deadline": {
        True: "Input freshness, retry schedules, and deadlines match the approved C0-A values.",
        False: "Input freshness, retry schedules, or deadlines are not implemented as approved in C0-A.",
    },
    "operational_comparison_tolerances": {
        True: "Operational comparison windows, tolerances, priorities, and rounding match C0-A.",
        False: "Operational comparison windows or field tolerances are not implemented as approved in C0-A.",
    },
    "approval_document_code_sha": {
        True: "All C0-A decisions and this collection share the validated approval record revisions.",
        False: "The C0-A approval revision evidence is incomplete.",
    },
}


def _registry_value_matches(value: str, expected: str) -> bool:
    return value == f"ADOPTED:{expected}"


def _eod_pair_is_coherent(raw: dict[str, str]) -> bool:
    if raw["latest_state"] == "MISSING" or raw["previous_state"] == "MISSING":
        return False
    latest = raw["latest_state"].split(",")
    previous = raw["previous_state"].split(",")
    return (
        len(latest) == 13
        and len(previous) == 13
        and latest[0] > previous[0]
        and latest[1] == "QQQM"
        and previous[1] == "QQQM"
        and latest[9] == "2"
        and previous[9] == "2"
        and raw["latest_date_row_count"] == "1"
        and raw["previous_date_row_count"] == "1"
        and _state_row_matches_c0a(raw["latest_state"])
        and _state_row_matches_c0a(raw["previous_state"])
    )


def _latest_job_is_completed(value: str) -> bool:
    fields = value.split(",")
    return len(fields) == 2 and _valid_iso_date(fields[0]) and fields[1] == "COMPLETED"


def _item5_live_environment_verified(
    config: dict[str, str], order: dict[str, str]
) -> bool:
    return config["live_process_environment_verification"] == (
        "VERIFIED_LIVE_PROCESS_ENVIRONMENT"
    ) and order["order_contract_live_process_environment"] == (
        "VERIFIED_LIVE_PROCESS_ENVIRONMENT"
    )


def _canonical_no_diff(
    item_id: str, captures: dict[str, dict[str, object]]
) -> bool:
    if item_id == "approval_document_code_sha":
        document_sha, code_sha, workflow_sha, run_id, run_attempt = (
            _approval_provenance_from_capture(captures[item_id])
        )
        return (
            document_sha == APPROVED_C0A_DOCUMENT_SHA
            and code_sha == APPROVED_C0A_CODE_SHA
            and HEX40.fullmatch(workflow_sha) is not None
            and re.fullmatch(r"[1-9][0-9]{0,19}", run_id) is not None
            and re.fullmatch(r"[1-9][0-9]{0,3}", run_attempt) is not None
        )
    raw = _remote_values_from_capture(captures[item_id], item_id)
    if item_id == "java_code_sha":
        # Known-deployment pair and local commit existence are enforced by the
        # directory validator before this predicate runs.  The C0-A v2 code SHA
        # is intentionally not compared to the independently deployed Java SHA.
        return raw["service_active_state"] == "active" and raw[
            "runtime_code_basis"
        ] == "running process jar predates start"
    if item_id == "effective_runtime_config":
        expected = {
            "spring_profile": "prod",
            "scheduling_enabled": "true",
            "execution_enabled": "false",
            "operation_mode": "PAPER",
            "server_binding": "127.0.0.1:8080",
            "service_sub_state": "running",
            "environment_file_contract": "EXPECTED",
            "exec_start_contract": "EXPECTED",
            "unit_fragment_contract": "EXPECTED",
            "drop_in_contract": "EXPECTED",
            "working_directory_contract": "EXPECTED",
            "external_config_contract": "EXPECTED",
            "file_and_unit_override_status": "NO_UNVERIFIED_OVERRIDE",
            "env_override_contract": "EXPECTED",
            "decision_config_override_contract": "EXPECTED",
            "runtime_config_basis": "running process matches file timestamps",
            "live_process_environment_verification": "VERIFIED_LIVE_PROCESS_ENVIRONMENT",
        }
        return raw == expected
    if item_id == "db_strategy_state":
        eod = _remote_values_from_capture(captures["eod_state_pair"], "eod_state_pair")
        required_state = (
            raw["latest_as_of_date"] != "MISSING"
            and raw["signal_symbol"] == "QQQM"
            and raw["strategy_on"] in {"0", "1"}
            and raw["version"] == "2"
            and raw["weights"] != "MISSING"
            and eod["latest_date_row_count"] == "1"
            and _state_row_matches_c0a(eod["latest_state"])
        )
        expected_registry = {
            "dd_bucket_parameter": "15 / 25 / 35 / 45%",
            "recovery_rule_parameter": "최대 DD 15% 이상 + 현재 DD 10% 이하",
            "rebalance_tolerance_parameter": "5.0%",
            "vix_threshold_parameter": "35",
            "ma_200_guard_parameter": "QQQM < MA200 시 공격 버킷 1단계 축소",
            "order_buffer_retry_policy_parameter": (
                "초기 BUY 0 tick / SELL 0 tick, 재시도 BUY +1 tick / SELL +1 tick, "
                "최대 3회, 대기 2000ms"
            ),
            "max_daily_turnover_parameter": "미정의 (0으로 비활성)",
            "max_order_notional_parameter": "미정의 (0으로 비활성)",
            "max_retry_exposure_parameter": "미정의 (0으로 비활성)",
            "max_slippage_parameter": "미정의 (0으로 비활성)",
        }
        return required_state and raw["registry_semantics"] == (
            "database record not runtime effective"
        ) and raw["database_snapshot_consistency"] == (
            "consistent read-only snapshot"
        ) and all(
            _registry_value_matches(raw[name], expected)
            for name, expected in expected_registry.items()
        )
    if item_id == "eod_state_pair":
        db = _remote_values_from_capture(captures["db_strategy_state"], "db_strategy_state")
        return _eod_pair_is_coherent(raw) and db[
            "database_snapshot_consistency"
        ] == "consistent read-only snapshot"
    if item_id == "order_mode_ownership_quantity":
        db = _remote_values_from_capture(captures["db_strategy_state"], "db_strategy_state")
        config = _remote_values_from_capture(
            captures["effective_runtime_config"], "effective_runtime_config"
        )
        expected_contract = {
            "operating_mode_db": "PAPER",
            "effective_mode": "PAPER",
            "effective_mode_source": "database record",
            "effective_mode_verification": "VERIFIED_DATABASE_RECORD",
            "execution_enabled": "false",
            "kill_switch_db": "OFF",
            "submission_guard_derivation": "BLOCKED_PAPER_MODE",
            "open_order_count": "0",
            # The approved SSH/DB route cannot prove the absence of another
            # account owner, so a truthful current capture remains DIFF here.
            "ownership_scope_verification": "verified exclusive Java ownership",
            "strategy_off_order_intent_policy": "zero intents",
            "quantity_policy": (
                "absolute target notional gap divided by limit price and rounded down"
            ),
            "risk_limit_application": "post-sizing order block",
            "buy_reference_price_policy": "best ask then last",
            "sell_reference_price_policy": "best bid then last",
            "order_price_rounding": "HALF_UP-2-decimals",
            "quantity_rounding": "DOWN-integer",
            "fee_pct": "0.25",
            "sell_proceeds_haircut": "0.995",
            "sell_quantity_cap": "integer owned position",
            "buy_quantity_cap": "remaining USD after fee",
            "cash_source_policy": (
                "KIS USD orderable cash plus fee-adjusted sell proceeds"
            ),
            "fx_quantity_policy": "FX excluded from order quantity",
            "sell_priority": "TQQQ-QLD-QQQM",
            "buy_priority": "QQQM-QLD-TQQQ",
            # The deployed code only blocks QQQ, not every unsupported holding.
            "unsupported_holding_guard": "any holding outside QQQM QLD TQQQ",
            "order_contract_source": "pinned deployed Java revision",
            "order_contract_live_process_environment": (
                "VERIFIED_LIVE_PROCESS_ENVIRONMENT"
            ),
        }
        return (
            _item5_live_environment_verified(config, raw)
            and _latest_job_is_completed(raw["latest_job"])
            and db["database_snapshot_consistency"]
            == "consistent read-only snapshot"
            and all(
                raw[name] == value for name, value in expected_contract.items()
            )
        )
    if item_id == "production_provider_contract":
        expected = {
            "quote_provider": "KIS current-file configuration without a network call",
            "auxiliary_provider": "Yahoo configured without a network call",
            "contract_source": "pinned deployed source blobs",
            "provider_network_call": "none",
            "provider_contract_verification": "VERIFIED_C0A_PRODUCTION_CONTRACT",
            "provider_live_process_environment": "VERIFIED_LIVE_PROCESS_ENVIRONMENT",
            "kis_origin_file_contract": "EXPECTED_PRODUCTION",
            "kis_quote_contract": "v1_해외주식-009",
            "kis_quote_method": "GET",
            "kis_quote_path": "/uapi/overseas-price/v1/quotations/price",
            "kis_auth_query": "AUTH empty",
            "kis_exchange_query": "EXCD NAS",
            "kis_symbol_query": "SYMB runtime signal symbol",
            "kis_session_scheme_contract": (
                "source scheme verified with credential value omitted"
            ),
            "kis_default_header_contract": (
                "JSON content type and configured application credentials"
            ),
            "kis_quote_transaction_prefix": "HHDFS",
            "kis_quote_transaction_digits_part_1": "0000",
            "kis_quote_transaction_digits_part_2": "0300",
            "kis_price_field": "output.base",
            "kis_status_validation": (
                "business result code not enforced before output.base use"
            ),
            "kis_connect_timeout_binding": (
                "Netty connect timeout from KIS properties"
            ),
            "kis_response_timeout_binding": (
                "Netty response and blocking timeout from KIS request property"
            ),
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
            "yahoo_connect_timeout_binding": (
                "Netty connect timeout from Yahoo properties"
            ),
            "yahoo_response_timeout_binding": (
                "Netty response and blocking timeout from Yahoo request property"
            ),
            "yahoo_official_status": "VERIFIED_OFFICIAL_PROVIDER_CONTRACT",
            "corporate_action_contract": "VERIFIED_C0A_CORPORATE_ACTION_CONTRACT",
        }
        return raw == expected
    if item_id == "price_market_time_semantics":
        return (
            raw["strategy_price_kind"] == "confirmed-raw-close"
            and raw["market_zone"] == "America/New_York"
            and raw["eod_schedule"] == "16:15-business-days"
            and raw["market_as_of_date_source"] == "exchange-calendar-market-date"
            and raw["upstream_observed_at"] != "NOT_PERSISTED"
            and raw["upstream_available_at"] != "NOT_PERSISTED"
        )
    if item_id == "kis_vix_ma200_semantics":
        return all(
            raw[name] not in {"NOT_PERSISTED", "UNVERIFIED_BY_APPROVED_ROUTE"}
            for name in (
                "base_upstream_date",
                "base_date_verification",
                "vix_observed_at",
                "ma_session_membership",
                "ma_price_adjustment",
            )
        ) and raw["ma_period"] == "200"
    if item_id == "freshness_retry_deadline":
        expected = {
            "input_freshness_enforcement": "60s-attempt-snapshot",
            "eod_input_retry_schedule": "+5m,+15m,+30m",
            "rebalance_input_retry_schedule": "+5m,+15m,+30m",
            "eod_deadline": "17:00-America/New_York",
            "rebalance_deadline": "10:30-America/New_York",
        }
        return all(raw[name] == value for name, value in expected.items())
    if item_id == "operational_comparison_tolerances":
        expected = {
            "rebalance_tolerance_pct": "5.0",
            "tick_size": "0.01",
            "price_rounding": "HALF_UP-2-decimals",
            "quantity_rounding": "DOWN-integer",
            "buy_priority": "QQQM-QLD-TQQQ",
            "sell_priority": "TQQQ-QLD-QQQM",
            "operational_comparison_window": "C0-B-approved-window",
            "operational_field_tolerances": "C0-B-approved-field-tolerances",
        }
        return raw == expected
    raise C0BError(f"comparison predicate is missing for {item_id}")


def deterministic_comparisons(
    captures: dict[str, dict[str, object]],
) -> list[dict[str, str]]:
    comparisons: list[dict[str, str]] = []
    for item_id in ITEM_IDS[:-1]:
        no_diff = _canonical_no_diff(item_id, captures)
        comparisons.append(
            {
                "id": item_id,
                "result": "NO_DIFF" if no_diff else "DIFF",
                "summary": COMPARISON_SUMMARIES[item_id][no_diff],
            }
        )

    aggregate = "DIFF" if any(item["result"] == "DIFF" for item in comparisons) else "NO_DIFF"
    comparisons.append(
        {
            "id": "c0a_diff_summary",
            "result": aggregate,
            "summary": "첫 11개 항목의 결정론적 비교 결과를 집계했다.",
        }
    )
    return comparisons


def _atomic_directory(output_dir: Path) -> tuple[Path, Path]:
    if output_dir.exists() or output_dir.is_symlink():
        raise C0BError("output directory must not already exist")
    parent = output_dir.parent
    if parent.is_symlink() or not parent.is_dir():
        raise C0BError("output parent must be an existing regular directory")
    temporary = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}.tmp-", dir=parent))
    os.chmod(temporary, 0o700)
    return temporary, output_dir


def build_bundle_atomic(
    *,
    capture_dir: Path,
    output_dir: Path,
    decision_file: Path,
    snapshot_at: str,
    compared_at: str,
    diff_collector: str,
    expected_workflow_sha: str,
    expected_workflow_run_id: str,
    expected_workflow_run_attempt: str,
    known_deployments_path: Path = DEFAULT_KNOWN_DEPLOYMENTS,
    repository_root: Path = DEFAULT_REPOSITORY_ROOT,
) -> None:
    captures = validate_capture_directory(
        capture_dir,
        known_deployments_path=known_deployments_path,
        repository_root=repository_root,
        decision_file=decision_file,
        expected_workflow_sha=expected_workflow_sha,
        expected_workflow_run_id=expected_workflow_run_id,
        expected_workflow_run_attempt=expected_workflow_run_attempt,
    )
    document_sha, code_sha, latest_c0a, collection_time = approval_metadata(decision_file)
    snapshot_time = parse_kst(snapshot_at, "snapshot time")
    compared_time = parse_kst(compared_at, "comparison time")
    comparisons = deterministic_comparisons(captures)
    if safe_evidence_text(diff_collector, "diff collector") != EXPECTED_DIFF_COLLECTOR:
        raise C0BError("diff collector is not the code-owned comparison route")

    substantive_comparisons = comparisons[:-1]
    overall_result = (
        "DIFF"
        if any(item["result"] == "DIFF" for item in substantive_comparisons)
        else "NO_DIFF"
    )
    diff_count = sum(item["result"] == "DIFF" for item in substantive_comparisons)
    no_diff_count = len(substantive_comparisons) - diff_count
    original_diff_summary = captures["c0a_diff_summary"]
    captures["c0a_diff_summary"] = capture_payload(
        "c0a_diff_summary",
        str(original_diff_summary["captured_at"]),
        str(original_diff_summary["collector"]),
        FINAL_DIFF_SUMMARY,
        [
            {"name": "substantive aggregate result", "value": overall_result},
            {"name": "different substantive item count", "value": str(diff_count)},
            {"name": "matching substantive item count", "value": str(no_diff_count)},
            {
                "name": "next gate",
                "value": "C0-B result reapproval required before C2 development",
            },
        ],
    )

    capture_times = [
        parse_kst(str(captures[item_id]["captured_at"]), f"capture {item_id}")
        for item_id in ITEM_IDS
    ]
    if latest_c0a > collection_time or any(time < collection_time for time in capture_times):
        raise C0BError("approval and capture time order is invalid")
    if snapshot_time < max(capture_times) or compared_time < snapshot_time:
        raise C0BError("snapshot and comparison time order is invalid")

    temporary, final_path = _atomic_directory(output_dir)
    try:
        item_dir = temporary / "items"
        diff_item_dir = temporary / "diff-items"
        item_dir.mkdir(mode=0o700)
        diff_item_dir.mkdir(mode=0o700)
        snapshot_items: list[dict[str, object]] = []
        diff_items: list[dict[str, object]] = []

        for index, item_id in enumerate(ITEM_IDS):
            capture_path = item_dir / f"{item_id}.json"
            capture_bytes = canonical_bytes(captures[item_id])
            with capture_path.open("xb") as output:
                output.write(capture_bytes)
            os.chmod(capture_path, 0o600)
            capture_sha = hashlib.sha256(capture_bytes).hexdigest()
            captured_at = str(captures[item_id]["captured_at"])
            capture_source = (
                f"docs/v2-cutover/evidence/c0b/items/{item_id}.json"
                f"#sha256={capture_sha}"
            )
            snapshot_items.append(
                {
                    "id": item_id,
                    "label": ITEM_LABELS[item_id],
                    "source": capture_source,
                    "captured_at": captured_at,
                    "sha256": capture_sha,
                }
            )

            comparison = comparisons[index]
            detail = {
                "schema_version": 1,
                "kind": "c0b_diff_detail",
                "id": item_id,
                "captured_at": captured_at,
                "collector": diff_collector,
                "sanitized": True,
                "result": comparison["result"],
                "summary": comparison["summary"],
            }
            detail_path = diff_item_dir / f"{item_id}.json"
            detail_bytes = canonical_bytes(detail)
            with detail_path.open("xb") as output:
                output.write(detail_bytes)
            os.chmod(detail_path, 0o600)
            detail_sha = hashlib.sha256(detail_bytes).hexdigest()
            diff_items.append(
                {
                    "id": item_id,
                    "result": comparison["result"],
                    "details_source": (
                        f"docs/v2-cutover/evidence/c0b/diff-items/{item_id}.json"
                        f"#sha256={detail_sha}"
                    ),
                    "details_sha256": detail_sha,
                }
            )

        snapshot = {
            "schema_version": 1,
            "kind": "c0b_snapshot_manifest",
            "captured_at": snapshot_at,
            "document_sha": document_sha,
            "code_sha": code_sha,
            "items": snapshot_items,
        }
        snapshot_path = temporary / "snapshot.json"
        snapshot_bytes = canonical_bytes(snapshot)
        with snapshot_path.open("xb") as output:
            output.write(snapshot_bytes)
        os.chmod(snapshot_path, 0o600)
        snapshot_sha = hashlib.sha256(snapshot_bytes).hexdigest()

        diff = {
            "schema_version": 1,
            "kind": "c0b_diff",
            "snapshot_manifest_sha256": snapshot_sha,
            "compared_at": compared_at,
            "result": overall_result,
            "items": diff_items,
        }
        diff_path = temporary / "diff.json"
        with diff_path.open("xb") as output:
            output.write(canonical_bytes(diff))
        os.chmod(diff_path, 0o600)

        validate_bundle_directory(
            temporary,
            decision_file=decision_file,
            expected_snapshot_at=snapshot_at,
            expected_compared_at=compared_at,
            known_deployments_path=known_deployments_path,
            repository_root=repository_root,
            expected_workflow_sha=expected_workflow_sha,
            expected_workflow_run_id=expected_workflow_run_id,
            expected_workflow_run_attempt=expected_workflow_run_attempt,
        )
        os.replace(temporary, final_path)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def _validate_diff_detail(
    payload: dict[str, object], expected_id: str, expected_time: datetime, expected_result: str
) -> None:
    exact_keys(
        payload,
        {
            "schema_version",
            "kind",
            "id",
            "captured_at",
            "collector",
            "sanitized",
            "result",
            "summary",
        },
        f"diff detail {expected_id}",
    )
    if payload["schema_version"] != 1 or payload["kind"] != "c0b_diff_detail":
        raise C0BError(f"diff detail {expected_id}: schema marker is invalid")
    if (
        payload["id"] != expected_id
        or payload["sanitized"] is not True
        or payload["result"] != expected_result
    ):
        raise C0BError(f"diff detail {expected_id}: metadata mismatch")
    captured_value = payload.get("captured_at")
    if not isinstance(captured_value, str) or parse_kst(
        captured_value, f"diff detail {expected_id}"
    ) != expected_time:
        raise C0BError(f"diff detail {expected_id}: captured time mismatch")
    safe_evidence_text(payload.get("collector"), f"diff detail {expected_id} collector")
    if payload.get("collector") != EXPECTED_DIFF_COLLECTOR:
        raise C0BError(f"diff detail {expected_id}: collector is not code-owned")
    safe_evidence_text(payload.get("summary"), f"diff detail {expected_id} summary")


def _reference_digest(reference: object, expected_path: str, context: str) -> str:
    if not isinstance(reference, str):
        raise C0BError(f"{context}: source reference must be text")
    match = re.fullmatch(r"([A-Za-z0-9._/-]+)#sha256=([0-9a-f]{64})", reference)
    if match is None or match.group(1) != expected_path:
        raise C0BError(f"{context}: source reference path is invalid")
    return match.group(2)


def validate_bundle_directory(
    path: Path,
    *,
    decision_file: Path,
    expected_snapshot_at: str | None = None,
    expected_compared_at: str | None = None,
    known_deployments_path: Path = DEFAULT_KNOWN_DEPLOYMENTS,
    repository_root: Path = DEFAULT_REPOSITORY_ROOT,
    expected_workflow_sha: str | None = None,
    expected_workflow_run_id: str | None = None,
    expected_workflow_run_attempt: str | None = None,
) -> tuple[str, str]:
    if path.is_symlink() or not path.is_dir():
        raise C0BError("bundle directory must be a regular directory")
    expected_entries = ["diff-items", "diff.json", "items", "snapshot.json"]
    entries = sorted(path.iterdir(), key=lambda value: value.name)
    if [entry.name for entry in entries] != expected_entries:
        raise C0BError("bundle directory contains unexpected entries")
    for entry in entries:
        if entry.is_symlink():
            raise C0BError("bundle symlinks are forbidden")

    captures = validate_capture_directory_for_bundle(
        path,
        known_deployments_path=known_deployments_path,
        repository_root=repository_root,
        decision_file=decision_file,
        expected_workflow_sha=expected_workflow_sha,
        expected_workflow_run_id=expected_workflow_run_id,
        expected_workflow_run_attempt=expected_workflow_run_attempt,
    )
    document_sha, code_sha, latest_c0a, collection_time = approval_metadata(decision_file)
    deterministic_expected = deterministic_comparisons(captures)
    capture_times = {
        item_id: parse_kst(str(captures[item_id]["captured_at"]), f"capture {item_id}")
        for item_id in ITEM_IDS
    }
    if latest_c0a > collection_time or any(
        value < collection_time for value in capture_times.values()
    ):
        raise C0BError("bundle approval and capture time order is invalid")

    snapshot_path = path / "snapshot.json"
    snapshot = load_json(snapshot_path, "snapshot")
    exact_keys(
        snapshot,
        {"schema_version", "kind", "captured_at", "document_sha", "code_sha", "items"},
        "snapshot",
    )
    if snapshot["schema_version"] != 1 or snapshot["kind"] != "c0b_snapshot_manifest":
        raise C0BError("snapshot schema marker is invalid")
    if snapshot["document_sha"] != document_sha or snapshot["code_sha"] != code_sha:
        raise C0BError("snapshot approval revisions do not match C0-A")
    snapshot_at_value = snapshot.get("captured_at")
    if not isinstance(snapshot_at_value, str):
        raise C0BError("snapshot captured_at must be text")
    snapshot_time = parse_kst(snapshot_at_value, "snapshot")
    if expected_snapshot_at is not None and snapshot_at_value != expected_snapshot_at:
        raise C0BError("snapshot time does not match expected value")
    if snapshot_time < max(capture_times.values()):
        raise C0BError("snapshot predates a capture")
    snapshot_items = snapshot.get("items")
    if not isinstance(snapshot_items, list) or len(snapshot_items) != len(ITEM_IDS):
        raise C0BError("snapshot requires all twelve items")
    for index, item in enumerate(snapshot_items):
        item_id = ITEM_IDS[index]
        if not isinstance(item, dict):
            raise C0BError("snapshot item must be an object")
        exact_keys(item, {"id", "label", "source", "captured_at", "sha256"}, "snapshot item")
        if item.get("id") != item_id or item.get("label") != ITEM_LABELS[item_id]:
            raise C0BError("snapshot item identity is invalid")
        if item.get("captured_at") != str(captures[item_id]["captured_at"]):
            raise C0BError("snapshot item time differs from capture")
        digest = _reference_digest(
            item.get("source"),
            f"docs/v2-cutover/evidence/c0b/items/{item_id}.json",
            "snapshot item",
        )
        if item.get("sha256") != digest or sha256_file(path / "items" / f"{item_id}.json") != digest:
            raise C0BError("snapshot item digest mismatch")

    snapshot_sha = sha256_file(snapshot_path)
    diff = load_json(path / "diff.json", "diff")
    exact_keys(
        diff,
        {"schema_version", "kind", "snapshot_manifest_sha256", "compared_at", "result", "items"},
        "diff",
    )
    if diff["schema_version"] != 1 or diff["kind"] != "c0b_diff":
        raise C0BError("diff schema marker is invalid")
    if diff["snapshot_manifest_sha256"] != snapshot_sha:
        raise C0BError("diff snapshot digest mismatch")
    compared_at_value = diff.get("compared_at")
    if not isinstance(compared_at_value, str):
        raise C0BError("diff compared_at must be text")
    compared_time = parse_kst(compared_at_value, "diff")
    if expected_compared_at is not None and compared_at_value != expected_compared_at:
        raise C0BError("diff time does not match expected value")
    if compared_time < snapshot_time:
        raise C0BError("diff predates snapshot")
    diff_items = diff.get("items")
    if not isinstance(diff_items, list) or len(diff_items) != len(ITEM_IDS):
        raise C0BError("diff requires all twelve items")
    observed_results: list[str] = []
    diff_dir = path / "diff-items"
    if diff_dir.is_symlink() or not diff_dir.is_dir():
        raise C0BError("diff-items must be a regular directory")
    expected_names = sorted(f"{item_id}.json" for item_id in ITEM_IDS)
    diff_files = sorted(diff_dir.iterdir(), key=lambda value: value.name)
    if [entry.name for entry in diff_files] != expected_names:
        raise C0BError("diff-items must contain exactly twelve named JSON files")
    for index, item in enumerate(diff_items):
        item_id = ITEM_IDS[index]
        if not isinstance(item, dict):
            raise C0BError("diff item must be an object")
        exact_keys(item, {"id", "result", "details_source", "details_sha256"}, "diff item")
        result = item.get("result")
        if item.get("id") != item_id or result not in {"NO_DIFF", "DIFF"}:
            raise C0BError("diff item identity or result is invalid")
        digest = _reference_digest(
            item.get("details_source"),
            f"docs/v2-cutover/evidence/c0b/diff-items/{item_id}.json",
            "diff item",
        )
        detail_path = diff_dir / f"{item_id}.json"
        if detail_path.is_symlink() or not detail_path.is_file():
            raise C0BError("diff detail must be a regular file")
        if item.get("details_sha256") != digest or sha256_file(detail_path) != digest:
            raise C0BError("diff detail digest mismatch")
        detail = load_json(detail_path, f"diff detail {item_id}")
        _validate_diff_detail(detail, item_id, capture_times[item_id], str(result))
        expected_item = deterministic_expected[index]
        if result != expected_item["result"] or detail.get("summary") != expected_item["summary"]:
            raise C0BError("diff item does not match deterministic comparison")
        observed_results.append(str(result))
    substantive_results = observed_results[:-1]
    expected_result = "DIFF" if "DIFF" in substantive_results else "NO_DIFF"
    if observed_results[-1] != expected_result:
        raise C0BError("C0-A diff summary item result contradicts substantive items")
    if diff.get("result") != expected_result:
        raise C0BError("overall diff result does not match item results")
    summary_data = captures["c0a_diff_summary"].get("data")
    if not isinstance(summary_data, dict) or not isinstance(summary_data.get("facts"), list):
        raise C0BError("C0-A diff summary capture is malformed")
    summary_facts = {
        fact.get("name"): fact.get("value")
        for fact in summary_data["facts"]
        if isinstance(fact, dict)
    }
    expected_summary_facts = {
        "substantive aggregate result": expected_result,
        "different substantive item count": str(substantive_results.count("DIFF")),
        "matching substantive item count": str(substantive_results.count("NO_DIFF")),
        "next gate": "C0-B result reapproval required before C2 development",
    }
    if summary_facts != expected_summary_facts:
        raise C0BError("C0-A diff summary does not match the validated diff items")
    return snapshot_sha, sha256_file(path / "diff.json")


def validate_capture_directory_for_bundle(
    path: Path,
    *,
    known_deployments_path: Path = DEFAULT_KNOWN_DEPLOYMENTS,
    repository_root: Path = DEFAULT_REPOSITORY_ROOT,
    decision_file: Path = DEFAULT_DECISION_FILE,
    expected_workflow_sha: str | None = None,
    expected_workflow_run_id: str | None = None,
    expected_workflow_run_attempt: str | None = None,
) -> dict[str, dict[str, object]]:
    """Validate a bundle's items directory using the same exact capture contract."""

    item_dir = path / "items"
    if item_dir.is_symlink() or not item_dir.is_dir():
        raise C0BError("bundle items must be a regular directory")
    expected_names = sorted(f"{item_id}.json" for item_id in ITEM_IDS)
    files = sorted(item_dir.iterdir(), key=lambda value: value.name)
    if [entry.name for entry in files] != expected_names:
        raise C0BError("bundle items must contain exactly twelve named JSON files")
    payloads: dict[str, dict[str, object]] = {}
    for item_id in ITEM_IDS:
        item_path = item_dir / f"{item_id}.json"
        if item_path.is_symlink() or not item_path.is_file():
            raise C0BError("bundle capture must be a regular file")
        payload = load_json(item_path, f"capture {item_id}")
        validate_capture(payload, item_id, final_bundle=True)
        payloads[item_id] = payload
    _validate_cross_capture_coherence(payloads)
    _validate_deployment_capture(payloads, known_deployments_path, repository_root)
    _validate_approval_capture(
        payloads,
        decision_file,
        repository_root,
        expected_workflow_sha=expected_workflow_sha,
        expected_workflow_run_id=expected_workflow_run_id,
        expected_workflow_run_attempt=expected_workflow_run_attempt,
    )
    return payloads
