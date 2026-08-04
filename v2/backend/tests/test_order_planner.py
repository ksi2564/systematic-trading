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


def test_위험한도에_도달해도_보호청산_매도는_허용한다() -> None:
    account_id = uuid4()
    version_id = uuid4()
    exit_result = EvaluationResult(
        as_of=date(2026, 4, 1),
        strategy_version_id=version_id,
        engine=StrategyEngine.SIGNAL_TRADING_V1,
        state={"exit_pending": True},
        target_weights={"AAPL": 0},
        base_target_weights={"AAPL": 0},
        events=["STOP_LOSS"],
    )

    plan = OrderPlanner().plan(
        account_id=account_id,
        strategy_version_id=version_id,
        result=exit_result,
        portfolio=Portfolio(
            cash=0,
            positions=(Position("AAPL", 10, 100, 100),),
        ),
        quotes={"AAPL": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy("100"),
        daily_usage=DailyRiskUsage(
            order_notional="200000",
            order_count=20,
            realized_loss="10000",
        ),
    )

    assert len(plan.intents) == 1
    assert plan.intents[0].side == OrderSide.SELL
    assert plan.intents[0].status == IntentStatus.PLANNED
    assert not plan.pause_required


def test_필수가격이_하나라도_없으면_부분주문을_계획하지_않는다() -> None:
    version_id = uuid4()

    plan = OrderPlanner().plan(
        account_id=uuid4(),
        strategy_version_id=version_id,
        result=result(version_id),
        portfolio=portfolio(),
        quotes={"QQQM": 100, "QLD": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy(),
    )

    assert plan.intents == ()
    assert plan.pause_required
    assert plan.reasons == ("TQQQ 필수 가격이 없습니다.",)


def test_개별종목의_새_신호와_미체결청산은_허용오차를_적용하지_않는다() -> None:
    account_id = uuid4()
    version_id = uuid4()
    entry_result = EvaluationResult(
        as_of=date(2026, 4, 1),
        strategy_version_id=version_id,
        engine=StrategyEngine.SIGNAL_TRADING_V1,
        state={"exit_pending": False},
        target_weights={"AAPL": 1},
        base_target_weights={"AAPL": 1},
        events=["SIGNAL_ENTRY"],
    )
    entry = OrderPlanner().plan(
        account_id=account_id,
        strategy_version_id=version_id,
        result=entry_result,
        portfolio=Portfolio(cash=Decimal("100000")),
        quotes={"AAPL": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy(),
    )
    exit_result = entry_result.model_copy(
        update={
            "state": {"exit_pending": True},
            "target_weights": {"AAPL": 0},
            "base_target_weights": {"AAPL": 0},
            "events": ["EXIT_PENDING"],
        }
    )
    exit_plan = OrderPlanner().plan(
        account_id=account_id,
        strategy_version_id=version_id,
        result=exit_result,
        portfolio=Portfolio(
            cash=Decimal("99000"),
            positions=(Position("AAPL", 10, 100, 100),),
        ),
        quotes={"AAPL": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy(),
    )

    assert len(entry.intents) == 1
    assert entry.intents[0].side == OrderSide.BUY
    assert len(exit_plan.intents) == 1
    assert exit_plan.intents[0].side == OrderSide.SELL
