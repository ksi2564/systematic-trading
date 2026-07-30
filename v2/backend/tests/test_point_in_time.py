from datetime import UTC, date, datetime
from decimal import Decimal

from wallant.domain.strategy import ConditionOperator, UniverseFilter
from wallant.research.point_in_time import (
    FundamentalObservation,
    HistoricalUniverseBuilder,
    PointInTimeFundamentalStore,
)


def test_공시전에는_재무데이터를_볼_수_없다() -> None:
    observation = FundamentalObservation(
        symbol="AAPL",
        period_end=date(2025, 3, 31),
        released_at=datetime(2025, 5, 1, 20, tzinfo=UTC),
        values={"market_cap": Decimal("1000")},
        provider="SEC",
        official=True,
    )
    store = PointInTimeFundamentalStore([observation])
    assert store.latest("AAPL", datetime(2025, 5, 1, 19, tzinfo=UTC)) is None
    assert store.latest("AAPL", datetime(2025, 5, 1, 21, tzinfo=UTC)) == observation


def test_비공식_과거종목군_재구성에는_경고를_남긴다() -> None:
    as_of = datetime(2025, 6, 1, tzinfo=UTC)
    store = PointInTimeFundamentalStore(
        [
            FundamentalObservation(
                symbol="AAA",
                period_end=date(2025, 3, 31),
                released_at=datetime(2025, 5, 1, tzinfo=UTC),
                values={"market_cap": Decimal("500")},
                provider="FREE",
                official=False,
            ),
            FundamentalObservation(
                symbol="BBB",
                period_end=date(2025, 3, 31),
                released_at=datetime(2025, 5, 1, tzinfo=UTC),
                values={"market_cap": Decimal("50")},
                provider="FREE",
                official=False,
            ),
        ]
    )
    snapshot = HistoricalUniverseBuilder(store).screen(
        as_of=as_of,
        filters=[
            UniverseFilter(
                field="market_cap",
                operator=ConditionOperator.GTE,
                value=Decimal("100"),
            )
        ],
        selection_limit=10,
        provider="FREE",
        source_has_historical_membership=False,
    )
    assert snapshot.symbols == ("AAA",)
    assert snapshot.warning
