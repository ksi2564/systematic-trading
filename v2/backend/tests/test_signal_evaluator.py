from datetime import date
from decimal import Decimal

import pytest

from wallant.domain.evaluator import EvaluatorRegistry
from wallant.domain.strategy import (
    EvaluationContext,
    Market,
    ProtectionRules,
    SignalKind,
    SignalTradingRules,
    StrategyDefinition,
    StrategyEngine,
    StrategyVersion,
    UniverseDefinition,
)


def signal_version(
    kind: SignalKind = SignalKind.PRICE_MA_CROSS,
    *,
    protections: ProtectionRules | None = None,
) -> StrategyVersion:
    return StrategyVersion(
        definition=StrategyDefinition(
            name="개별종목 신호 전략",
            engine=StrategyEngine.SIGNAL_TRADING_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["AAPL"]),
            signal_symbol="AAPL",
            signal_rules=SignalTradingRules(kind=kind, ma_period=3),
            protections=protections or ProtectionRules(),
        )
    )


def test_이동평균_상향돌파는_새_신호에서만_진입한다() -> None:
    version = signal_version()
    registry = EvaluatorRegistry()
    first = registry.evaluate(
        version,
        EvaluationContext(
            as_of=date(2026, 1, 1),
            market={"AAPL.close": 110},
            history={"AAPL": [Decimal("100"), Decimal("100"), Decimal("100")]},
            portfolio={"position_open": False},
        ),
    )
    repeated = registry.evaluate(
        version,
        EvaluationContext(
            as_of=date(2026, 1, 2),
            market={"AAPL.close": 111},
            history={"AAPL": [Decimal("100"), Decimal("100"), Decimal("110")]},
            previous_state=first.state,
            portfolio={"position_open": False},
        ),
    )

    assert first.target_weights == {"AAPL": Decimal("100")}
    assert "SIGNAL_ENTRY" in first.events
    assert repeated.target_weights == {"AAPL": Decimal("0")}
    assert "SIGNAL_ENTRY" not in repeated.events


@pytest.mark.parametrize(
    ("rules", "history", "close"),
    (
        (
            SignalTradingRules(kind=SignalKind.MA_CROSS, fast_period=2, slow_period=3),
            [10, 10, 9],
            12,
        ),
        (
            SignalTradingRules(kind=SignalKind.HIGH_BREAKOUT, breakout_period=3),
            [10, 11, 10],
            12,
        ),
        (
            SignalTradingRules(
                kind=SignalKind.RSI_RECOVERY,
                rsi_period=2,
                rsi_entry_threshold=30,
                rsi_exit_threshold=70,
            ),
            [100, 90, 80],
            90,
        ),
    ),
)
def test_지원하는_개별종목_신호가_새_진입을_만든다(
    rules: SignalTradingRules,
    history: list[int],
    close: int,
) -> None:
    version = StrategyVersion(
        definition=StrategyDefinition(
            name="신호 종류 테스트",
            engine=StrategyEngine.SIGNAL_TRADING_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["AAPL"]),
            signal_symbol="AAPL",
            signal_rules=rules,
        )
    )

    result = EvaluatorRegistry().evaluate(
        version,
        EvaluationContext(
            as_of=date(2026, 1, 1),
            market={"AAPL.close": close},
            history={"AAPL": [Decimal(value) for value in history]},
            high_history={"AAPL": [Decimal(value) for value in history]},
            low_history={"AAPL": [Decimal(value) for value in history]},
            portfolio={"position_open": False},
        ),
    )

    assert result.target_weights == {"AAPL": Decimal("100")}
    assert "SIGNAL_ENTRY" in result.events


def test_고점돌파는_이전_장중고가와_저가를_사용한다() -> None:
    version = StrategyVersion(
        definition=StrategyDefinition(
            name="실제 고점 돌파",
            engine=StrategyEngine.SIGNAL_TRADING_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["AAPL"]),
            signal_symbol="AAPL",
            signal_rules=SignalTradingRules(
                kind=SignalKind.HIGH_BREAKOUT,
                breakout_period=2,
            ),
        )
    )

    result = EvaluatorRegistry().evaluate(
        version,
        EvaluationContext(
            as_of=date(2026, 1, 3),
            market={"AAPL.close": 110},
            history={"AAPL": [Decimal("100"), Decimal("100")]},
            high_history={"AAPL": [Decimal("120"), Decimal("115")]},
            low_history={"AAPL": [Decimal("90"), Decimal("95")]},
            portfolio={"position_open": False},
        ),
    )

    assert result.target_weights == {"AAPL": Decimal("0")}
    assert "SIGNAL_ENTRY" not in result.events


def test_손절_익절_트레일링은_실제_매입가와_최고가로_평가한다() -> None:
    registry = EvaluatorRegistry()
    cases = (
        (ProtectionRules(stop_loss_pct=5), Decimal("94"), {}, "STOP_LOSS"),
        (ProtectionRules(take_profit_pct=5), Decimal("106"), {}, "TAKE_PROFIT"),
        (
            ProtectionRules(trailing_stop_pct=10),
            Decimal("115"),
            {"peak_price": "130"},
            "TRAILING_STOP",
        ),
    )
    for protections, close, previous_state, event in cases:
        result = registry.evaluate(
            signal_version(protections=protections),
            EvaluationContext(
                as_of=date(2026, 1, 2),
                market={"AAPL.close": close},
                history={"AAPL": [Decimal("50"), Decimal("50"), Decimal("50")]},
                previous_state={"entry_condition": False, **previous_state},
                portfolio={"position_open": True, "average_price": Decimal("100")},
            ),
        )
        assert result.target_weights == {"AAPL": Decimal("0")}
        assert result.base_target_weights == {"AAPL": Decimal("100")}
        assert event in result.events


def test_미체결_청산은_보유가_확인되는_동안_목표와_최고가를_유지한다() -> None:
    version = signal_version(protections=ProtectionRules(trailing_stop_pct=10))
    registry = EvaluatorRegistry()
    first = registry.evaluate(
        version,
        EvaluationContext(
            as_of=date(2026, 1, 2),
            market={"AAPL.close": Decimal("115")},
            history={"AAPL": [Decimal("100"), Decimal("100"), Decimal("100")]},
            previous_state={"entry_condition": False, "peak_price": "130"},
            portfolio={"position_open": True, "average_price": Decimal("100")},
        ),
    )
    still_open = registry.evaluate(
        version,
        EvaluationContext(
            as_of=date(2026, 1, 3),
            market={"AAPL.close": Decimal("116")},
            history={"AAPL": [Decimal("100"), Decimal("115"), Decimal("115")]},
            previous_state=first.state,
            portfolio={"position_open": True, "average_price": Decimal("100")},
        ),
    )

    assert first.state["exit_pending"] is True
    assert first.state["peak_price"] == "130"
    assert still_open.target_weights == {"AAPL": Decimal("0")}
    assert still_open.state["exit_pending"] is True
    assert still_open.state["peak_price"] == "130"


def test_전략_체크섬은_json_키_순서와_무관하다() -> None:
    left = signal_version().definition.model_copy(deep=True)
    right = signal_version().definition.model_copy(deep=True)
    left.parameters = {"alpha": 1, "beta": {"x": 2, "y": 3}}
    right.parameters = {"beta": {"y": 3, "x": 2}, "alpha": 1}

    assert left.model_dump() == right.model_dump()
    assert left.checksum() == right.checksum()
