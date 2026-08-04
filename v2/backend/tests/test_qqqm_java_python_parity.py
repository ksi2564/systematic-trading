import json
from datetime import date
from decimal import Decimal
from pathlib import Path

from wallant.domain.defaults import qqqm_drawdown_version
from wallant.domain.evaluator import EvaluatorRegistry
from wallant.domain.execution import OrderPlanner, RiskPolicy
from wallant.domain.portfolio import Portfolio, Position
from wallant.domain.strategy import (
    EvaluationContext,
    EvaluationResult,
    StrategyEngine,
    StrategyVersion,
)
from wallant.research.backtest import BacktestEngine
from wallant.research.models import MarketBar
from wallant.research.paper import PaperSession, PaperTradingService

GOLDEN_PATH = (
    Path(__file__).resolve().parents[3]
    / "src/test/resources/strategy-parity/qqqm_java_python_golden.json"
)


def golden() -> dict:
    return json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))


def weights(payload: dict[str, str]) -> dict[str, Decimal]:
    return {symbol: Decimal(value) for symbol, value in payload.items()}


def state(payload: dict, *, target_key: str = "target") -> dict:
    return {
        "signal_symbol": "QQQM",
        "ath": payload["ath"],
        "last_close": payload["close"],
        "drawdown_pct": payload["drawdown"],
        "max_drawdown_pct_since_ath": payload["max_drawdown"],
        "drawdown_bucket": payload["bucket"],
        "phase": payload["phase"],
        "base_target_weights": payload[target_key],
        "strategy_on": True,
    }


def evaluation_context(
    *,
    close: str,
    ma: str | None = None,
    vix: str | None = None,
    previous_state: dict | None = None,
    previous_target: dict[str, Decimal] | None = None,
    as_of: str = "2026-04-01",
) -> EvaluationContext:
    return EvaluationContext(
        as_of=date.fromisoformat(as_of),
        market={
            "QQQM.close": Decimal(close),
            "signal_ma_200": Decimal(ma) if ma is not None else None,
            "vix": Decimal(vix) if vix is not None else None,
        },
        history={"QQQM": [Decimal("100")]},
        previous_state=previous_state or {},
        previous_target_weights=previous_target or {},
    )


def test_python_matches_shared_drawdown_and_recovery_golden_cases() -> None:
    fixture = golden()
    version = qqqm_drawdown_version()
    registry = EvaluatorRegistry()
    normal_state = {
        "signal_symbol": "QQQM",
        "ath": "100",
        "last_close": "95",
        "drawdown_pct": "5.0000",
        "max_drawdown_pct_since_ath": "5.0000",
        "drawdown_bucket": "LESS_THAN_15",
        "phase": "NORMAL",
        "base_target_weights": {"QQQM": "100", "QLD": "0", "TQQQ": "0"},
        "strategy_on": True,
    }

    for case in fixture["drawdown_boundaries"]:
        result = registry.evaluate(
            version,
            evaluation_context(close=case["close"], previous_state=normal_state),
        )
        assert result.state["drawdown_pct"] == case["expected_drawdown"]
        assert result.state["drawdown_bucket"] == case["expected_bucket"]
        assert result.base_target_weights == weights(case["expected_target"])
        assert result.target_weights == weights(case["expected_target"])
        assert sum(result.target_weights.values()) == Decimal("100")

    for case in fixture["recovery_hysteresis"]:
        previous = case["previous"]
        result = registry.evaluate(
            version,
            evaluation_context(
                close=case["close"],
                previous_state=state(previous),
                previous_target=weights(previous["target"]),
            ),
        )
        assert result.state["drawdown_pct"] == case["expected_drawdown"]
        assert result.state["phase"] == case["expected_phase"]
        assert result.base_target_weights == weights(case["expected_target"])
        assert result.target_weights == weights(case["expected_target"])
        assert sum(result.target_weights.values()) == Decimal("100")


def test_python_matches_shared_guard_boundaries_and_ma_then_vix_order() -> None:
    fixture = golden()
    version = qqqm_drawdown_version()
    registry = EvaluatorRegistry()

    for case in fixture["guard_boundaries"]:
        result = registry.evaluate(
            version,
            evaluation_context(close=case["close"], ma=case["ma"], vix=case["vix"]),
        )
        assert ("MA200_DEFENSIVE" in result.events) is case["expected_ma_triggered"]
        assert ("VIX_NO_LEVERAGE_INCREASE" in result.events) is case["expected_vix_triggered"]
        assert sum(result.target_weights.values()) == Decimal("100")

    case = fixture["ma_then_vix"]
    result = registry.evaluate(
        version,
        evaluation_context(
            close=case["close"],
            ma=case["ma"],
            vix=case["vix"],
            previous_state={
                "signal_symbol": "QQQM",
                "ath": "100",
                "last_close": "55",
                "drawdown_pct": "45.0000",
                "max_drawdown_pct_since_ath": "45.0000",
                "drawdown_bucket": "MORE_THAN_45",
                "phase": "DRAWDOWN",
                "base_target_weights": case["original"],
                "strategy_on": True,
            },
            previous_target=weights(case["previous_base"]),
        ),
    )
    assert result.base_target_weights == weights(case["original"])
    assert result.target_weights == weights(case["expected_target"])
    assert result.events == case["expected_events"]
    assert sum(result.target_weights.values()) == Decimal("100")


def test_paper_carries_the_base_target_across_consecutive_high_vix_days() -> None:
    fixture = golden()
    version = qqqm_drawdown_version()
    session = PaperSession()
    service = PaperTradingService()
    portfolio = Portfolio(cash=Decimal("100000"))
    risk_policy = RiskPolicy(
        max_buy_order_notional="100000",
        max_daily_buy_notional="300000",
        max_daily_order_count=20,
        max_symbol_weight_pct=100,
        max_daily_loss="100000",
    )

    for case in fixture["paper_sequence"]:
        step = service.step(
            session=session,
            version=version,
            context=evaluation_context(
                as_of=case["as_of"],
                close=case["close"],
                ma=case["ma"],
                vix=case["vix"],
            ),
            portfolio=portfolio,
            quotes={"QQQM": Decimal("50"), "QLD": Decimal("30"), "TQQQ": Decimal("20")},
            risk_policy=risk_policy,
        )
        assert step.evaluation.base_target_weights == weights(case["expected_base"])
        assert step.evaluation.target_weights == weights(case["expected_target"])
        assert step.evaluation.events == case["expected_events"]
        assert session.previous_target_weights == weights(case["expected_base"])
        assert sum(step.evaluation.target_weights.values()) == Decimal("100")


def test_backtest_carries_the_base_target_across_consecutive_high_vix_days() -> None:
    fixture = golden()
    definition = qqqm_drawdown_version().definition.model_copy(deep=True)
    definition.parameters["ma_period"] = 2
    version = StrategyVersion(definition=definition)
    market_bars: list[MarketBar] = []
    for case in fixture["backtest_sequence"]:
        trading_date = date.fromisoformat(case["as_of"])
        qqqm_close = Decimal(case["close"])
        for symbol, close in (
            ("QQQM", qqqm_close),
            ("QLD", qqqm_close * Decimal("0.7")),
            ("TQQQ", qqqm_close * Decimal("0.5")),
            ("VIX", Decimal(case["vix"])),
        ):
            market_bars.append(
                MarketBar(
                    trading_date=trading_date,
                    symbol=symbol,
                    open=close,
                    high=close,
                    low=close,
                    close=close,
                    volume=1000,
                )
            )

    result = BacktestEngine().run(version, market_bars, initial_cash=Decimal("100000"))

    assert len(result.evaluations) == len(fixture["backtest_sequence"])
    for evaluation, case in zip(result.evaluations, fixture["backtest_sequence"], strict=True):
        assert evaluation.base_target_weights == weights(case["expected_base"])
        assert evaluation.target_weights == weights(case["expected_target"])
        assert sum(evaluation.target_weights.values()) == Decimal("100")


def test_backtest_matches_shared_order_tolerance_gate_and_priority() -> None:
    fixture = golden()
    engine = BacktestEngine()

    for case in fixture["order_tolerance_and_priority"]:
        portfolio_payload = case["portfolio"]
        trading_date = date(2026, 4, 2)
        holdings = {
            position["symbol"]: Decimal(position["quantity"])
            for position in portfolio_payload["positions"]
        }
        average_prices = {
            position["symbol"]: Decimal(position["market_price"])
            for position in portfolio_payload["positions"]
        }
        daily = {
            position["symbol"]: MarketBar(
                trading_date=trading_date,
                symbol=position["symbol"],
                open=Decimal(position["market_price"]),
                high=Decimal(position["market_price"]),
                low=Decimal(position["market_price"]),
                close=Decimal(position["market_price"]),
                volume=1000,
            )
            for position in portfolio_payload["positions"]
        }
        holdings["SPY"] = Decimal("7")
        average_prices["SPY"] = Decimal("500")

        _, trades = engine._rebalance_at_open(
            trading_date=trading_date,
            signal_date=date(2026, 4, 1),
            target=weights(case["target"]),
            daily=daily,
            cash=Decimal(portfolio_payload["cash"]),
            holdings=holdings,
            average_prices=average_prices,
            fee_rate=Decimal("0"),
            tolerance_pct=Decimal(case["tolerance_pct"]),
            force_rebalance=False,
            sell_priority=["TQQQ", "QLD", "QQQM"],
            buy_priority=["QQQM", "QLD", "TQQQ"],
        )

        actual_intents = [{"symbol": trade.symbol, "side": trade.side} for trade in trades]
        assert bool(trades) is case["expected_should_rebalance"], case["id"]
        assert actual_intents == case["expected_intents"], case["id"]
        assert holdings["SPY"] == Decimal("7"), case["id"]
        assert average_prices["SPY"] == Decimal("500"), case["id"]


def test_python_matches_shared_order_tolerance_gate_and_priority() -> None:
    fixture = golden()
    planner = OrderPlanner()

    for case in fixture["order_tolerance_and_priority"]:
        version = qqqm_drawdown_version()
        portfolio_payload = case["portfolio"]
        positions = tuple(
            Position(
                symbol=position["symbol"],
                quantity=Decimal(position["quantity"]),
                average_price=Decimal(position["market_price"]),
                market_price=Decimal(position["market_price"]),
            )
            for position in portfolio_payload["positions"]
        )
        portfolio = Portfolio(
            cash=Decimal(portfolio_payload["cash"]),
            positions=positions,
        )
        target = weights(case["target"])
        evaluation = EvaluationResult(
            as_of=date(2026, 4, 1),
            strategy_version_id=version.id,
            engine=StrategyEngine.QQQM_DRAWDOWN_V2,
            state={},
            target_weights=target,
            base_target_weights=target,
        )
        plan = planner.plan(
            account_id=version.id,
            strategy_version_id=version.id,
            result=evaluation,
            portfolio=portfolio,
            quotes={position.symbol: position.market_price for position in positions},
            tolerance_pct=Decimal(case["tolerance_pct"]),
            risk_policy=RiskPolicy(
                max_buy_order_notional="100000000",
                max_daily_buy_notional="100000000",
                max_daily_order_count=20,
                max_symbol_weight_pct=100,
                max_daily_loss="100000000",
            ),
        )

        actual_intents = [
            {"symbol": intent.symbol, "side": intent.side.value}
            for intent in plan.intents
        ]
        assert bool(plan.intents) is case["expected_should_rebalance"], case["id"]
        assert actual_intents == case["expected_intents"], case["id"]
