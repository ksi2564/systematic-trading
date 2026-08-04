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
from wallant.research.paper import PaperTradingService


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
        max_buy_order_notional=max_order,
        max_daily_buy_notional="200000",
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
    assert "MAX_BUY_ORDER_NOTIONAL" in plan.reasons


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
            buy_notional="200000",
            order_count=19,
            realized_loss="10000",
        ),
    )

    assert len(plan.intents) == 1
    assert plan.intents[0].side == OrderSide.SELL
    assert plan.intents[0].status == IntentStatus.PLANNED
    assert not plan.pause_required


def test_보호청산도_전체주문횟수를_넘으면_수동확인을_요구한다() -> None:
    version_id = uuid4()
    exit_portfolio = Portfolio(
        cash=0,
        positions=(Position("AAPL", 10, 100, 100),),
    )
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
        account_id=uuid4(),
        strategy_version_id=version_id,
        result=exit_result,
        portfolio=exit_portfolio,
        quotes={"AAPL": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy(),
        daily_usage=DailyRiskUsage(order_count=20),
    )

    assert len(plan.intents) == 1
    assert plan.intents[0].side == OrderSide.SELL
    assert plan.intents[0].status == IntentStatus.CONFIRMATION_REQUIRED
    assert plan.pause_required
    assert plan.reasons == ("MAX_DAILY_ORDER_COUNT",)
    assert PaperTradingService._realized_loss(plan, exit_portfolio) == 0


def test_매수금액과_전체주문횟수는_종목입력순서와_무관하게_계산한다() -> None:
    version_id = uuid4()
    account_id = uuid4()
    targets = (
        {"BUY": 100, "SELL": 0},
        {"SELL": 0, "BUY": 100},
    )
    plans = []
    for target_weights in targets:
        allocation = EvaluationResult(
            as_of=date(2026, 4, 1),
            strategy_version_id=version_id,
            engine=StrategyEngine.RULE_ALLOCATION_V1,
            state={},
            target_weights=target_weights,
            base_target_weights=target_weights,
        )
        plans.append(
            OrderPlanner().plan(
                account_id=account_id,
                strategy_version_id=version_id,
                result=allocation,
                portfolio=Portfolio(
                    cash=0,
                    positions=(Position("SELL", 10, 100, 100),),
                ),
                quotes={"BUY": 100, "SELL": 100},
                tolerance_pct=Decimal("0"),
                risk_policy=RiskPolicy(
                    max_buy_order_notional="1000",
                    max_daily_buy_notional="1000",
                    max_daily_order_count=2,
                    max_symbol_weight_pct=100,
                    max_daily_loss=1000,
                ),
                sell_priority=["SELL"],
                buy_priority=["BUY"],
            )
        )

    assert [intent.side for intent in plans[0].intents] == [OrderSide.SELL, OrderSide.BUY]
    assert [intent.status for intent in plans[0].intents] == [
        IntentStatus.PLANNED,
        IntentStatus.PLANNED,
    ]
    assert plans[0] == plans[1]


def test_같은쪽_주문도_입력순서가_아니라_종목코드순으로_한도를_적용한다() -> None:
    version_id = uuid4()
    account_id = uuid4()
    plans = []
    for target_weights in ({"BBB": 50, "AAA": 50}, {"AAA": 50, "BBB": 50}):
        allocation = EvaluationResult(
            as_of=date(2026, 4, 1),
            strategy_version_id=version_id,
            engine=StrategyEngine.RULE_ALLOCATION_V1,
            state={},
            target_weights=target_weights,
            base_target_weights=target_weights,
        )
        plans.append(
            OrderPlanner().plan(
                account_id=account_id,
                strategy_version_id=version_id,
                result=allocation,
                portfolio=Portfolio(cash=1000),
                quotes={"AAA": 100, "BBB": 100},
                tolerance_pct=Decimal("0"),
                risk_policy=RiskPolicy(
                    max_buy_order_notional="500",
                    max_daily_buy_notional="500",
                    max_daily_order_count=1,
                    max_symbol_weight_pct=100,
                    max_daily_loss=1000,
                ),
            )
        )

    assert [intent.symbol for intent in plans[0].intents] == ["AAA", "BBB"]
    assert [intent.status for intent in plans[0].intents] == [
        IntentStatus.PLANNED,
        IntentStatus.BLOCKED,
    ]
    assert plans[0] == plans[1]


def test_전략대상이_아닌_보유종목은_자동매매하지_않고_수동확인을_요구한다() -> None:
    version_id = uuid4()

    plan = OrderPlanner().plan(
        account_id=uuid4(),
        strategy_version_id=version_id,
        result=result(version_id),
        portfolio=Portfolio(
            cash=0,
            positions=(Position("SPY", 10, 100, 100),),
        ),
        quotes={"QQQM": 100, "QLD": 100, "TQQQ": 100},
        tolerance_pct=Decimal("5"),
        risk_policy=policy(),
    )

    assert plan.intents == ()
    assert plan.pause_required
    assert plan.reasons == ("전략 관리 대상이 아닌 SPY 보유분의 수동 확인이 필요합니다.",)


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
