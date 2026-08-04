from datetime import date
from decimal import Decimal

import pytest

from wallant.domain.defaults import qqqm_drawdown_version
from wallant.domain.evaluator import EvaluatorRegistry
from wallant.domain.strategy import (
    EvaluationContext,
    Market,
    StrategyDefinition,
    StrategyEngine,
    StrategyVersion,
    UniverseDefinition,
)


def evaluate(
    close: str,
    *,
    previous_state: dict | None = None,
    previous_target: dict | None = None,
    history: list[str] | None = None,
    vix: str | None = None,
    ma: str | None = None,
    portfolio: dict | None = None,
):
    version = qqqm_drawdown_version()
    return EvaluatorRegistry().evaluate(
        version,
        EvaluationContext(
            as_of=date(2025, 12, 2),
            market={
                "QQQM.close": Decimal(close),
                "vix": Decimal(vix) if vix else None,
                "signal_ma_200": Decimal(ma) if ma else None,
            },
            history={"QQQM": [Decimal(value) for value in history or []]},
            previous_state=previous_state or {},
            previous_target_weights=previous_target or {},
            portfolio=portfolio or {},
        ),
    )


def state(
    *,
    ath: str,
    close: str,
    drawdown: str,
    max_drawdown: str,
    phase: str,
    weights: dict[str, str],
    strategy_on: bool = True,
) -> dict:
    return {
        "signal_symbol": "QQQM",
        "ath": ath,
        "last_close": close,
        "drawdown_pct": drawdown,
        "max_drawdown_pct_since_ath": max_drawdown,
        "phase": phase,
        "base_target_weights": weights,
        "strategy_on": strategy_on,
    }


def test_신고가에서_낙폭과_최대낙폭을_초기화한다() -> None:
    result = evaluate(
        "101.23",
        previous_state=state(
            ath="100",
            close="95",
            drawdown="5",
            max_drawdown="5",
            phase="NORMAL",
            weights={"QQQM": "100", "QLD": "0", "TQQQ": "0"},
        ),
    )
    assert result.state["ath"] == "101.23"
    assert Decimal(result.state["drawdown_pct"]) == Decimal("0")
    assert Decimal(result.state["max_drawdown_pct_since_ath"]) == Decimal("0")
    assert result.state["phase"] == "NORMAL"
    assert result.target_weights == {
        "QQQM": Decimal("100.00"),
        "QLD": Decimal("0.00"),
        "TQQQ": Decimal("0.00"),
    }


def test_15퍼센트_낙폭에서_새_버킷과_비중을_계산한다() -> None:
    result = evaluate(
        "85",
        previous_state=state(
            ath="100",
            close="95",
            drawdown="5",
            max_drawdown="5",
            phase="NORMAL",
            weights={"QQQM": "100", "QLD": "0", "TQQQ": "0"},
        ),
    )
    assert result.state["drawdown_pct"] == "15.0000"
    assert result.state["phase"] == "DRAWDOWN"
    assert result.state["drawdown_bucket"] == "FROM_15_TO_25"
    assert result.target_weights["TQQQ"] == Decimal("10.00")


def test_회복_직전_구간에서는_이전_비중을_유지한다() -> None:
    previous_weights = {"QQQM": "60", "QLD": "30", "TQQQ": "10"}
    result = evaluate(
        "88",
        previous_state=state(
            ath="100",
            close="82",
            drawdown="18",
            max_drawdown="20",
            phase="DRAWDOWN",
            weights=previous_weights,
        ),
        previous_target=previous_weights,
    )
    assert result.state["drawdown_pct"] == "12.0000"
    assert result.state["phase"] == "DRAWDOWN"
    assert result.base_target_weights["TQQQ"] == Decimal("10")


def test_10퍼센트까지_회복하면_recovery로_전환한다() -> None:
    result = evaluate(
        "90",
        previous_state=state(
            ath="100",
            close="82",
            drawdown="18",
            max_drawdown="20",
            phase="DRAWDOWN",
            weights={"QQQM": "60", "QLD": "30", "TQQQ": "10"},
        ),
    )
    assert result.state["phase"] == "RECOVERY"
    assert result.target_weights == {
        "QQQM": Decimal("70.00"),
        "QLD": Decimal("30.00"),
        "TQQQ": Decimal("0.00"),
    }


def test_초기상태는_과거_365일_종가에서_ath를_찾는다() -> None:
    result = evaluate("82", history=["90", "100", "95"])
    assert result.state["ath"] == "100"
    assert result.state["drawdown_pct"] == "18.0000"
    assert result.state["phase"] == "DRAWDOWN"


def test_초기_ath는_설정한_조회기간_안에서만_계산한다() -> None:
    definition = qqqm_drawdown_version().definition.model_copy(deep=True)
    definition.parameters["ath_lookup_days"] = 2
    version = StrategyVersion(definition=definition)
    result = EvaluatorRegistry().evaluate(
        version,
        EvaluationContext(
            as_of=date(2025, 12, 2),
            market={"QQQM.close": Decimal("90")},
            history={"QQQM": [Decimal("200"), Decimal("100"), Decimal("95")]},
        ),
    )
    assert result.state["ath"] == "100"


def test_vix는_tqqq_증가를_이전_비중으로_제한한다() -> None:
    previous_weights = {"QQQM": Decimal("60"), "QLD": Decimal("30"), "TQQQ": Decimal("10")}
    result = evaluate(
        "70",
        previous_state=state(
            ath="100",
            close="82",
            drawdown="18",
            max_drawdown="20",
            phase="DRAWDOWN",
            weights={"QQQM": "60", "QLD": "30", "TQQQ": "10"},
        ),
        previous_target=previous_weights,
        vix="40",
        ma="60",
    )
    assert result.base_target_weights["TQQQ"] == Decimal("20.00")
    assert result.target_weights == previous_weights
    assert "VIX_NO_LEVERAGE_INCREASE" in result.events


def test_첫평가의_vix도_현재비중보다_tqqq를_늘리지_않는다() -> None:
    result = evaluate(
        "70",
        history=["100"],
        vix="40",
        ma="60",
        portfolio={"TQQQ.weight_pct": Decimal("5")},
    )

    assert result.base_target_weights["TQQQ"] == Decimal("20.00")
    assert result.target_weights["TQQQ"] == Decimal("5")
    assert "VIX_NO_LEVERAGE_INCREASE" in result.events


def test_200일선_필터는_tqqq를_한_단계_낮춘다() -> None:
    result = evaluate(
        "50",
        previous_state=state(
            ath="100",
            close="55",
            drawdown="45",
            max_drawdown="45",
            phase="DRAWDOWN",
            weights={"QQQM": "20", "QLD": "20", "TQQQ": "60"},
        ),
        ma="60",
    )
    assert result.base_target_weights["TQQQ"] == Decimal("60.00")
    assert result.target_weights == {
        "QQQM": Decimal("30.00"),
        "QLD": Decimal("30.00"),
        "TQQQ": Decimal("40.00"),
    }
    assert "MA200_DEFENSIVE" in result.events


def test_qqqm_전용엔진의_종목군은_임의로_바꿀_수_없다() -> None:
    with pytest.raises(ValueError, match="고정"):
        StrategyDefinition(
            name="잘못된 QQQM 전략",
            engine=StrategyEngine.QQQM_DRAWDOWN_V2,
            market=Market.US,
            universe=UniverseDefinition(symbols=["QQQM", "QLD"]),
            signal_symbol="QQQM",
        )
