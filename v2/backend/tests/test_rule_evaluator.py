from datetime import date
from decimal import Decimal

import pytest

from wallant.domain.evaluator import EvaluatorRegistry
from wallant.domain.strategy import (
    AllocationRule,
    Condition,
    ConditionGroup,
    ConditionOperator,
    EvaluationContext,
    Market,
    Operand,
    StrategyDefinition,
    StrategyEngine,
    StrategyVersion,
    UniverseDefinition,
)


def test_폼_규칙은_우선순위에_따라_목표비중을_선택한다() -> None:
    defensive = AllocationRule(
        name="시장 약세",
        priority=1,
        when=ConditionGroup(
            conditions=[
                Condition(
                    left=Operand(source="market", key="SPY.close"),
                    operator=ConditionOperator.LT,
                    right=Operand(source="market", key="SPY.ma_200"),
                )
            ]
        ),
        target_weights={"SHY": 100},
        next_state="DEFENSIVE",
    )
    normal = AllocationRule(
        name="기본",
        priority=99,
        when=ConditionGroup(
            conditions=[
                Condition(
                    left=Operand(source="constant", value=1),
                    operator=ConditionOperator.EQ,
                    right=Operand(source="constant", value=1),
                )
            ]
        ),
        target_weights={"SPY": 100},
        next_state="NORMAL",
    )
    version = StrategyVersion(
        definition=StrategyDefinition(
            name="규칙 전략",
            engine=StrategyEngine.RULE_ALLOCATION_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["SPY", "SHY"]),
            signal_symbol="SPY",
            rules=[normal, defensive],
        )
    )
    result = EvaluatorRegistry().evaluate(
        version,
        EvaluationContext(
            as_of=date(2026, 1, 1),
            market={"SPY.close": Decimal("90"), "SPY.ma_200": Decimal("100")},
        ),
    )
    assert result.state["name"] == "DEFENSIVE"
    assert result.target_weights == {
        "SHY": Decimal("100"),
        "SPY": Decimal("0"),
    }


def test_규칙전략에는_개별종목_손절보호를_섞을_수_없다() -> None:
    rule = AllocationRule(
        name="기본",
        priority=1,
        when=ConditionGroup(
            conditions=[
                Condition(
                    left=Operand(source="constant", value=1),
                    operator=ConditionOperator.EQ,
                    right=Operand(source="constant", value=1),
                )
            ]
        ),
        target_weights={"SPY": 100},
    )
    with pytest.raises(ValueError, match="개별종목 신호 전략"):
        StrategyDefinition(
            name="손절 규칙 전략",
            engine=StrategyEngine.RULE_ALLOCATION_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["SPY"]),
            signal_symbol="SPY",
            rules=[rule],
            protections={"stop_loss_pct": 10},
        )


def test_규칙의_목표종목과_우선순위는_모호할_수_없다() -> None:
    rule = AllocationRule(
        name="기본",
        priority=1,
        when=ConditionGroup(
            conditions=[
                Condition(
                    left=Operand(source="constant", value=1),
                    operator=ConditionOperator.EQ,
                    right=Operand(source="constant", value=1),
                )
            ]
        ),
        target_weights={"SHY": 100},
    )
    with pytest.raises(ValueError, match="고정 종목군"):
        StrategyDefinition(
            name="잘못된 전략",
            engine=StrategyEngine.RULE_ALLOCATION_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["SPY"]),
            signal_symbol="SPY",
            rules=[rule],
        )
