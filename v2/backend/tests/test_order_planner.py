from datetime import date
from decimal import Decimal
from uuid import uuid4

from wallant.domain.execution import (
    DailyRiskUsage,
    IntentStatus,
    OrderPlanner,
    OrderSide,
    RiskPolicy,
)
from wallant.domain.portfolio import Portfolio, Position
from wallant.domain.strategy import EvaluationResult, StrategyEngine


def result(version_id):
    return EvaluationResult(
        as_of=date(2026, 4, 1),
        strategy_version_id=version_id,
        engine=StrategyEngine.QQQM_DRAWDOWN_V2,
        state={},
        target_weights={"QQQM": 60, "QLD": 30, "TQQQ": 10},
        base_target_weights={"QQQM": 60, "QLD": 30, "TQQQ": 10},
    )


def policy(max_order: str = "100000") -> RiskPolicy:
    return RiskPolicy(
        max_order_notional=max_order,
        max_daily_notional="200000",
        max_daily_order_count=20,
        max_symbol_weight_pct=100,
        max_daily_loss=10000,
    )


def portfolio() -> Portfolio:
    return Portfolio(
        cash=0,
        positions=(
            Position("QQQM", 2, 100, 100),
            Position("QLD", 1, 100, 100),
            Position("TQQQ", 7, 100, 100),
        ),
    )


def test_매도를_매수보다_먼저_계획하고_멱등키를_고정한다() -> None:
    account_id = uuid4()
    version_id = uuid4()
    planner = OrderPlanner()
    first = planner.plan(
        account_id=account_id,
        strategy_version_id=version_id,
        result=result(version_id),
        portfolio=portfolio(),
        quotes={"QQQM": 100, "QLD": 100, "TQQQ": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy(),
    )
    second = planner.plan(
        account_id=account_id,
        strategy_version_id=version_id,
        result=result(version_id),
        portfolio=portfolio(),
        quotes={"QQQM": 100, "QLD": 100, "TQQQ": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy(),
    )
    assert first.intents[0].side == OrderSide.SELL
    assert [item.idempotency_key for item in first.intents] == [
        item.idempotency_key for item in second.intents
    ]
    assert not first.pause_required


def test_단일주문_한도_위반은_계좌정지를_요구한다() -> None:
    account_id = uuid4()
    version_id = uuid4()
    plan = OrderPlanner().plan(
        account_id=account_id,
        strategy_version_id=version_id,
        result=result(version_id),
        portfolio=portfolio(),
        quotes={"QQQM": 100, "QLD": 100, "TQQQ": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy("100"),
    )
    assert plan.pause_required
    assert any(item.status == IntentStatus.BLOCKED for item in plan.intents)
    assert "MAX_ORDER_NOTIONAL" in plan.reasons


def test_일일손실이_한도에_도달하면_새주문을_차단한다() -> None:
    account_id = uuid4()
    version_id = uuid4()
    plan = OrderPlanner().plan(
        account_id=account_id,
        strategy_version_id=version_id,
        result=result(version_id),
        portfolio=portfolio(),
        quotes={"QQQM": 100, "QLD": 100, "TQQQ": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy(),
        daily_usage=DailyRiskUsage(realized_loss="10000"),
    )
    assert plan.pause_required
    assert "MAX_DAILY_LOSS" in plan.reasons
