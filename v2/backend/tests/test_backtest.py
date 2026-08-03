from datetime import date, timedelta
from decimal import Decimal

from wallant.domain.defaults import qqqm_drawdown_version
from wallant.domain.strategy import (
    AllocationRule,
    Condition,
    ConditionGroup,
    ConditionOperator,
    Market,
    Operand,
    SignalKind,
    SignalTradingRules,
    StrategyDefinition,
    StrategyEngine,
    StrategyVersion,
    UniverseDefinition,
)
from wallant.research.backtest import BacktestEngine
from wallant.research.models import (
    CorporateAction,
    CorporateActionKind,
    MarketBar,
)
from wallant.research.rolling import RollingValidationService


def bars(days: int = 8) -> list[MarketBar]:
    result = []
    start = date(2025, 1, 1)
    for index in range(days):
        day = start + timedelta(days=index)
        qqqm = Decimal("100") - Decimal(index * 3)
        for symbol, ratio in (("QQQM", 1), ("QLD", 0.7), ("TQQQ", 0.5)):
            close = qqqm * Decimal(str(ratio))
            result.append(
                MarketBar(
                    trading_date=day,
                    symbol=symbol,
                    open=close + 1,
                    high=close + 2,
                    low=close - 1,
                    close=close,
                    volume=1000,
                )
            )
        result.append(
            MarketBar(
                trading_date=day,
                symbol="VIX",
                open=20,
                high=21,
                low=19,
                close=20,
                volume=0,
            )
        )
    return result


def test_종가_평가의_주문은_다음_거래일_시가에_체결한다() -> None:
    result = BacktestEngine().run(
        qqqm_drawdown_version(),
        bars(),
        initial_cash=Decimal("10000"),
    )
    assert result.trades
    assert all(trade.trading_date > trade.signal_date for trade in result.trades)
    assert result.metrics.evaluated_days == 8
    assert result.metrics.trade_count == len(result.trades)
    assert any("비공식 연구 데이터" in warning for warning in result.warnings)


def test_배당과_분할을_포트폴리오에_반영한다() -> None:
    actions = [
        CorporateAction(
            action_date=date(2025, 1, 3),
            symbol="QQQM",
            kind=CorporateActionKind.CASH_DIVIDEND,
            value=Decimal("1"),
        ),
        CorporateAction(
            action_date=date(2025, 1, 4),
            symbol="QQQM",
            kind=CorporateActionKind.SPLIT,
            value=Decimal("2"),
        ),
    ]
    with_actions = BacktestEngine().run(
        qqqm_drawdown_version(),
        bars(),
        initial_cash=Decimal("10000"),
        corporate_actions=actions,
    )
    without_actions = BacktestEngine().run(
        qqqm_drawdown_version(),
        bars(),
        initial_cash=Decimal("10000"),
    )
    assert with_actions.metrics.final_equity != without_actions.metrics.final_equity


def test_롤링검증은_파라미터를_바꾸지_않고_여러_창을_평가한다() -> None:
    result = RollingValidationService().run(
        qqqm_drawdown_version(),
        bars(10),
        window_days=5,
        step_days=2,
    )
    assert result.fixed_parameters
    assert len(result.windows) == 3
    assert any("비공식 연구 데이터" in warning for warning in result.warnings)


def test_같은_날짜와_종목의_중복가격은_거부한다() -> None:
    duplicated = bars()
    duplicated.append(duplicated[0])
    try:
        BacktestEngine().run(qqqm_drawdown_version(), duplicated)
    except ValueError as exc:
        assert "중복 가격" in str(exc)
    else:
        raise AssertionError("중복 가격 행이 허용되었습니다.")


def test_보유종목_시가가_누락되면_전체_리밸런싱을_다음날까지_보류한다() -> None:
    weak = AllocationRule(
        name="약세",
        priority=1,
        when=ConditionGroup(
            conditions=[
                Condition(
                    left=Operand(source="market", key="VTI.close"),
                    operator=ConditionOperator.LT,
                    right=Operand(source="constant", value=100),
                )
            ]
        ),
        target_weights={"SHY": 100},
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
    )
    version = StrategyVersion(
        definition=StrategyDefinition(
            name="누락 시가 회귀 테스트",
            engine=StrategyEngine.RULE_ALLOCATION_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["VTI", "SPY", "SHY"]),
            signal_symbol="VTI",
            rules=[weak, normal],
        )
    )
    daily_prices = (
        (date(2025, 1, 1), {"VTI": 110, "SPY": 100, "SHY": 80}),
        (date(2025, 1, 2), {"VTI": 90, "SPY": 100, "SHY": 80}),
        (date(2025, 1, 3), {"VTI": 90, "SHY": 80}),
        (date(2025, 1, 4), {"VTI": 90, "SPY": 100, "SHY": 80}),
    )
    market_bars = [
        MarketBar(
            trading_date=trading_date,
            symbol=symbol,
            open=price,
            high=price + 1,
            low=price - 1,
            close=price,
            volume=1000,
        )
        for trading_date, prices in daily_prices
        for symbol, price in prices.items()
    ]

    result = BacktestEngine().run(version, market_bars, initial_cash=Decimal("10000"))

    assert any(
        "2025-01-03: 필수 가격이 누락되어 주문과 평가를 보류합니다: SPY" in warning
        for warning in result.warnings
    )
    assert any(
        trade.side == "SELL"
        and trade.symbol == "SPY"
        and trade.signal_date == date(2025, 1, 2)
        and trade.trading_date == date(2025, 1, 4)
        for trade in result.trades
    )


def test_규칙이_요구하는_이동평균은_관측치가_쌓일_때까지_워밍업한다() -> None:
    defensive = AllocationRule(
        name="3일선 아래",
        priority=1,
        when=ConditionGroup(
            conditions=[
                Condition(
                    left=Operand(source="market", key="SPY.close"),
                    operator=ConditionOperator.LT,
                    right=Operand(source="market", key="SPY.ma_3"),
                )
            ]
        ),
        target_weights={"SHY": 100},
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
    )
    version = StrategyVersion(
        definition=StrategyDefinition(
            name="이동평균 워밍업",
            engine=StrategyEngine.RULE_ALLOCATION_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["SPY", "SHY"]),
            signal_symbol="SPY",
            parameters={"ma_period": 3},
            rules=[defensive, normal],
        )
    )
    prices = (100, 90, 80, 79, 78)
    market_bars = [
        MarketBar(
            trading_date=date(2025, 2, 1) + timedelta(days=index),
            symbol=symbol,
            open=price,
            high=price + 1,
            low=price - 1,
            close=price,
            volume=1000,
        )
        for index, spy_price in enumerate(prices)
        for symbol, price in (("SPY", spy_price), ("SHY", 80))
    ]

    result = BacktestEngine().run(version, market_bars)

    assert result.metrics.start_date == date(2025, 2, 3)
    assert result.metrics.evaluated_days == 3
    assert any("첫 2개 평가일을 워밍업" in warning for warning in result.warnings)
    assert any(trade.side == "BUY" and trade.symbol == "SHY" for trade in result.trades)


def test_개별종목_손절은_매입가_기준으로_백테스트에_반영한다() -> None:
    version = StrategyVersion(
        definition=StrategyDefinition(
            name="손절 백테스트",
            engine=StrategyEngine.SIGNAL_TRADING_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["AAPL"]),
            signal_symbol="AAPL",
            signal_rules=SignalTradingRules(kind=SignalKind.PRICE_MA_CROSS, ma_period=2),
            protections={"stop_loss_pct": 5},
        )
    )
    closes = (100, 100, 110, 90, 90)
    market_bars = [
        MarketBar(
            trading_date=date(2025, 3, 1) + timedelta(days=index),
            symbol="AAPL",
            open=Decimal("110") if index == 3 else close,
            high=max(Decimal(close), Decimal("110")),
            low=min(Decimal(close), Decimal("90")),
            close=close,
            volume=1000,
        )
        for index, close in enumerate(closes)
    ]

    result = BacktestEngine().run(version, market_bars, initial_cash=Decimal("10000"))

    assert any("STOP_LOSS" in evaluation.events for evaluation in result.evaluations)
    assert any(trade.side == "SELL" for trade in result.trades)


def test_목표비중과_현재비중_차이가_허용오차_미만이면_재거래하지_않는다() -> None:
    balanced = AllocationRule(
        name="균형",
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
        target_weights={"AAA": 50, "BBB": 50},
    )
    version = StrategyVersion(
        definition=StrategyDefinition(
            name="허용 오차",
            engine=StrategyEngine.RULE_ALLOCATION_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["AAA", "BBB"]),
            signal_symbol="AAA",
            tolerance_pct=5,
            rules=[balanced],
        )
    )
    prices = ((100, 100), (100, 100), (104, 96), (104, 96))
    market_bars = [
        MarketBar(
            trading_date=date(2025, 4, 1) + timedelta(days=index),
            symbol=symbol,
            open=price,
            high=price,
            low=price,
            close=price,
            volume=1000,
        )
        for index, pair in enumerate(prices)
        for symbol, price in zip(("AAA", "BBB"), pair, strict=True)
    ]

    result = BacktestEngine().run(version, market_bars, initial_cash=Decimal("10000"))

    assert len(result.trades) == 2


def test_롤링_후속창은_이전_데이터를_지표_워밍업에_사용한다() -> None:
    rule = AllocationRule(
        name="MA 아래",
        priority=1,
        when=ConditionGroup(
            conditions=[
                Condition(
                    left=Operand(source="market", key="SPY.close"),
                    operator=ConditionOperator.LT,
                    right=Operand(source="market", key="SPY.ma_3"),
                )
            ]
        ),
        target_weights={"SPY": 100},
    )
    version = StrategyVersion(
        definition=StrategyDefinition(
            name="롤링 워밍업",
            engine=StrategyEngine.RULE_ALLOCATION_V1,
            market=Market.US,
            universe=UniverseDefinition(symbols=["SPY"]),
            signal_symbol="SPY",
            parameters={"ma_period": 3},
            rules=[rule],
        )
    )
    market_bars = [
        MarketBar(
            trading_date=date(2025, 5, 1) + timedelta(days=index),
            symbol="SPY",
            open=100 - index,
            high=101 - index,
            low=99 - index,
            close=100 - index,
            volume=1000,
        )
        for index in range(8)
    ]

    result = RollingValidationService().run(version, market_bars, window_days=5, step_days=2)

    assert result.windows[1].metrics.start_date == date(2025, 5, 3)
    assert result.windows[1].metrics.evaluated_days == 5
