from wallant.domain.execution import AccountStatus
from wallant.domain.safety import ExecutionGate, ExecutionGateInput
from wallant.domain.strategy import StrategyLifecycle


def safe_input(**updates) -> ExecutionGateInput:
    values = {
        "execution_enabled": True,
        "broker_adapter": "kis",
        "global_emergency_paused": False,
        "account_status": AccountStatus.ACTIVE,
        "strategy_lifecycle": StrategyLifecycle.LIVE_APPROVED,
        "credentials_configured": True,
        "required_data_available": True,
        "all_live_inputs_official": True,
        "unresolved_order_count": 0,
    }
    values.update(updates)
    return ExecutionGateInput(**values)


def test_모든_안전조건을_통과해야만_실행을_허용한다() -> None:
    assert ExecutionGate().check(safe_input()).allowed


def test_비공식데이터와_미해결주문은_실행을_차단한다() -> None:
    decision = ExecutionGate().check(safe_input(all_live_inputs_official=False, unresolved_order_count=1))
    assert not decision.allowed
    assert decision.reasons == ("UNOFFICIAL_LIVE_DATA", "UNRESOLVED_ORDER")


def test_브로커_자격증명이_없으면_실행을_차단한다() -> None:
    decision = ExecutionGate().check(safe_input(credentials_configured=False))
    assert not decision.allowed
    assert decision.reasons == ("BROKER_CREDENTIALS_MISSING",)


def test_v2_mvp_기본값은_실행을_이중차단한다() -> None:
    decision = ExecutionGate().check(safe_input(execution_enabled=False, broker_adapter="disabled"))
    assert not decision.allowed
    assert "EXECUTION_DISABLED" in decision.reasons
    assert "BROKER_DISABLED" in decision.reasons
