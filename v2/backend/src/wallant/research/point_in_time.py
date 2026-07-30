from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Any

from wallant.domain.strategy import ConditionOperator, UniverseFilter


@dataclass(frozen=True, slots=True)
class FundamentalObservation:
    symbol: str
    period_end: date
    released_at: datetime
    values: dict[str, Decimal | str]
    provider: str
    official: bool


@dataclass(frozen=True, slots=True)
class UniverseSnapshot:
    as_of: datetime
    symbols: tuple[str, ...]
    provider: str
    warning: str | None
    evidence_count: int


class PointInTimeFundamentalStore:
    """공시 시각 이후에만 관측값을 노출해 미래 참조를 막는다."""

    def __init__(self, observations: list[FundamentalObservation]) -> None:
        self._observations = tuple(observations)

    def latest(self, symbol: str, as_of: datetime) -> FundamentalObservation | None:
        normalized = symbol.strip().upper()
        available = [
            observation
            for observation in self._observations
            if observation.symbol.strip().upper() == normalized and observation.released_at <= as_of
        ]
        return max(available, key=lambda item: (item.period_end, item.released_at), default=None)

    def available_symbols(self, as_of: datetime) -> set[str]:
        return {
            observation.symbol.strip().upper()
            for observation in self._observations
            if observation.released_at <= as_of
        }


class HistoricalUniverseBuilder:
    def __init__(self, store: PointInTimeFundamentalStore) -> None:
        self.store = store

    def screen(
        self,
        *,
        as_of: datetime,
        filters: list[UniverseFilter],
        selection_limit: int,
        provider: str,
        source_has_historical_membership: bool,
    ) -> UniverseSnapshot:
        matched: list[tuple[str, FundamentalObservation]] = []
        for symbol in sorted(self.store.available_symbols(as_of)):
            observation = self.store.latest(symbol, as_of)
            if observation and all(self._matches(observation.values, item) for item in filters):
                matched.append((symbol, observation))
        selected = tuple(symbol for symbol, _observation in matched[:selection_limit])
        warning = None
        if not source_has_historical_membership:
            warning = (
                "공식 과거 구성종목 스냅샷이 없어 당시 공개된 재무 관측값으로 종목군을 최선 추정했습니다."
            )
        return UniverseSnapshot(
            as_of=as_of,
            symbols=selected,
            provider=provider,
            warning=warning,
            evidence_count=len(matched),
        )

    @staticmethod
    def _matches(
        values: dict[str, Decimal | str],
        item: UniverseFilter,
    ) -> bool:
        if item.field not in values:
            return False
        actual: Any = values[item.field]
        expected: Any = item.value
        if item.operator == ConditionOperator.EQ:
            return actual == expected
        if item.operator == ConditionOperator.NE:
            return actual != expected
        if item.operator == ConditionOperator.GT:
            return actual > expected
        if item.operator == ConditionOperator.GTE:
            return actual >= expected
        if item.operator == ConditionOperator.LT:
            return actual < expected
        if item.operator == ConditionOperator.LTE:
            return actual <= expected
        if not isinstance(expected, list) or len(expected) != 2:
            return False
        return expected[0] <= actual <= expected[1]
