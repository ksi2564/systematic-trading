from __future__ import annotations

from decimal import Decimal

from wallant.domain.strategy import (
    DataRequirement,
    Market,
    Resolution,
    StrategyDefinition,
    StrategyEngine,
    StrategyVersion,
    UniverseDefinition,
    UniverseKind,
)


def qqqm_drawdown_definition() -> StrategyDefinition:
    return StrategyDefinition(
        name="QQQM 낙폭 분할매수 v2 기준선",
        description="기존 Java 전략의 실제 상태 전이와 주문 직전 보호 필터를 재현합니다.",
        engine=StrategyEngine.QQQM_DRAWDOWN_V2,
        market=Market.US,
        universe=UniverseDefinition(
            kind=UniverseKind.FIXED,
            market=Market.US,
            symbols=["QQQM", "QLD", "TQQQ"],
        ),
        signal_symbol="QQQM",
        tolerance_pct=Decimal("5.0"),
        parameters={
            "drawdown_thresholds": ["15", "25", "35", "45"],
            "recovery_activation_max_drawdown_pct": "15",
            "recovery_drawdown_pct": "10",
            "ath_lookup_days": 365,
            "circuit_breaker_enabled": True,
            "vix_enabled": True,
            "vix_threshold": "35",
            "ma_period": 200,
            "sell_priority": ["TQQQ", "QLD", "QQQM"],
            "buy_priority": ["QQQM", "QLD", "TQQQ"],
        },
        data_requirements=[
            DataRequirement(
                key="QQQM",
                resolution=Resolution.DAY,
                fields=["open", "high", "low", "close", "volume"],
            ),
            DataRequirement(
                key="QLD",
                resolution=Resolution.DAY,
                fields=["open", "high", "low", "close", "volume"],
            ),
            DataRequirement(
                key="TQQQ",
                resolution=Resolution.DAY,
                fields=["open", "high", "low", "close", "volume"],
            ),
            DataRequirement(
                key="VIX",
                resolution=Resolution.DAY,
                fields=["close"],
            ),
        ],
    )


def qqqm_drawdown_version() -> StrategyVersion:
    return StrategyVersion(definition=qqqm_drawdown_definition())
