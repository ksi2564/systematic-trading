from __future__ import annotations

from dataclasses import dataclass

from wallant.domain.execution import AccountStatus
from wallant.domain.strategy import StrategyLifecycle


@dataclass(frozen=True, slots=True)
class ExecutionGateInput:
    execution_enabled: bool
    broker_adapter: str
    global_emergency_paused: bool
    account_status: AccountStatus
    strategy_lifecycle: StrategyLifecycle
    credentials_configured: bool
    required_data_available: bool
    all_live_inputs_official: bool
    unresolved_order_count: int


@dataclass(frozen=True, slots=True)
class ExecutionGateDecision:
    allowed: bool
    reasons: tuple[str, ...]


class ExecutionGate:
    def check(self, value: ExecutionGateInput) -> ExecutionGateDecision:
        reasons: list[str] = []
        if not value.execution_enabled:
            reasons.append("EXECUTION_DISABLED")
        if value.broker_adapter == "disabled":
            reasons.append("BROKER_DISABLED")
        if value.global_emergency_paused:
            reasons.append("GLOBAL_EMERGENCY_PAUSED")
        if value.account_status != AccountStatus.ACTIVE:
            reasons.append("ACCOUNT_PAUSED")
        if value.strategy_lifecycle != StrategyLifecycle.LIVE_APPROVED:
            reasons.append("STRATEGY_NOT_APPROVED")
        if not value.credentials_configured:
            reasons.append("BROKER_CREDENTIALS_MISSING")
        if not value.required_data_available:
            reasons.append("REQUIRED_DATA_UNAVAILABLE")
        if not value.all_live_inputs_official:
            reasons.append("UNOFFICIAL_LIVE_DATA")
        if value.unresolved_order_count > 0:
            reasons.append("UNRESOLVED_ORDER")
        return ExecutionGateDecision(
            allowed=not reasons,
            reasons=tuple(reasons),
        )
