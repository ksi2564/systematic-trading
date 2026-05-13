package my.side.trading.core.application.strategy;

import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.config.TradingStrategyProps;
import my.side.trading.core.infrastructure.config.TradingStrategyThresholdProps;
import my.side.trading.testutil.FakeSignalHistoricalDataProvider;
import my.side.trading.testutil.FakeStrategyStateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyStateEodServiceTest {

    private final FakeSignalHistoricalDataProvider emptyDataProvider = new FakeSignalHistoricalDataProvider();

    @Test
    void 신고가에서는Drawdown과최대Drawdown을초기화한다() {
        StrategyState prev = state(
                new BigDecimal("100.00"),
                new BigDecimal("95.00"),
                new BigDecimal("5.0000"),
                new BigDecimal("5.0000"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.normal(),
                true);

        StrategyState next = service(new FakeStrategyStateRepository(prev), emptyDataProvider)
                .runEod(LocalDate.of(2025, 12, 2), new BigDecimal("101.23"));

        assertThat(next.ath()).isEqualByComparingTo("101.23");
        assertThat(next.drawdownPct()).isEqualByComparingTo("0.0000");
        assertThat(next.maxDrawdownPctSinceAth()).isEqualByComparingTo("0.0000");
        assertThat(next.phase()).isEqualTo(StrategyPhase.NORMAL);
        assertThat(next.targetWeights()).isEqualTo(WeightSet.normal());
        assertThat(next.strategyOn()).isTrue();
    }

    @Test
    void drawdown구간진입시새버킷과비중을계산한다() {
        StrategyState prev = state(
                new BigDecimal("100.00"),
                new BigDecimal("95.00"),
                new BigDecimal("5.0000"),
                new BigDecimal("5.0000"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.normal(),
                true);

        StrategyState next = service(new FakeStrategyStateRepository(prev), emptyDataProvider)
                .runEod(LocalDate.of(2025, 12, 2), new BigDecimal("85.00"));

        assertThat(next.drawdownPct()).isEqualByComparingTo("15.0000");
        assertThat(next.maxDrawdownPctSinceAth()).isEqualByComparingTo("15.0000");
        assertThat(next.phase()).isEqualTo(StrategyPhase.DRAWDOWN);
        assertThat(next.ddBucket()).isEqualTo(DdBucket.FROM_15_TO_25);
        assertThat(next.targetWeights()).isEqualTo(WeightSet.of(60, 30, 10));
    }

    @Test
    void recovery직전구간에서는이전비중을유지한다() {
        StrategyState prev = state(
                new BigDecimal("100.00"),
                new BigDecimal("82.00"),
                new BigDecimal("18.0000"),
                new BigDecimal("20.0000"),
                DdBucket.FROM_15_TO_25,
                StrategyPhase.DRAWDOWN,
                WeightSet.of(60, 30, 10),
                true);

        StrategyState next = service(new FakeStrategyStateRepository(prev), emptyDataProvider)
                .runEod(LocalDate.of(2025, 12, 2), new BigDecimal("88.00"));

        assertThat(next.drawdownPct()).isEqualByComparingTo("12.0000");
        assertThat(next.phase()).isEqualTo(StrategyPhase.DRAWDOWN);
        assertThat(next.targetWeights()).isEqualTo(prev.targetWeights());
    }

    @Test
    void recovery조건을만족하면RecoveryPhase로전환한다() {
        StrategyState prev = state(
                new BigDecimal("100.00"),
                new BigDecimal("82.00"),
                new BigDecimal("18.0000"),
                new BigDecimal("20.0000"),
                DdBucket.FROM_15_TO_25,
                StrategyPhase.DRAWDOWN,
                WeightSet.of(60, 30, 10),
                true);

        StrategyState next = service(new FakeStrategyStateRepository(prev), emptyDataProvider)
                .runEod(LocalDate.of(2025, 12, 2), new BigDecimal("90.00"));

        assertThat(next.drawdownPct()).isEqualByComparingTo("10.0000");
        assertThat(next.phase()).isEqualTo(StrategyPhase.RECOVERY);
        assertThat(next.targetWeights()).isEqualTo(WeightSet.recovery());
    }

    @Test
    void strategyOn은이전상태를유지한다() {
        StrategyState prev = state(
                new BigDecimal("100.00"),
                new BigDecimal("95.00"),
                new BigDecimal("5.0000"),
                new BigDecimal("5.0000"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.normal(),
                false);

        StrategyState next = service(new FakeStrategyStateRepository(prev), emptyDataProvider)
                .runEod(LocalDate.of(2025, 12, 2), new BigDecimal("85.00"));

        assertThat(next.phase()).isEqualTo(StrategyPhase.DRAWDOWN);
        assertThat(next.strategyOn()).isFalse();
    }

    @Test
    void 초기상태가없으면과거데이터로Ath를계산한다() {
        FakeSignalHistoricalDataProvider dataProvider = new FakeSignalHistoricalDataProvider(List.of(
                new BigDecimal("90.00"),
                new BigDecimal("100.00"),
                new BigDecimal("95.00")));

        StrategyState result = service(new FakeStrategyStateRepository(null), dataProvider)
                .runEod(LocalDate.of(2025, 12, 10), new BigDecimal("82.00"));

        assertThat(result.ath()).isEqualByComparingTo("100.00");
        assertThat(result.drawdownPct()).isEqualByComparingTo("18.0000");
        assertThat(result.phase()).isEqualTo(StrategyPhase.DRAWDOWN);
        assertThat(result.ddBucket()).isEqualTo(DdBucket.FROM_15_TO_25);
        assertThat(result.targetWeights()).isEqualTo(WeightSet.of(60, 30, 10));
        assertThat(result.signalSymbol()).isEqualTo("QQQM");
        assertThat(dataProvider.requestedSymbol()).isEqualTo("QQQM");
    }

    @Test
    void 기존상태가_QQQ_기준이면_QQQM_과거데이터로_새로_초기화한다() {
        StrategyState oldQqqState = new StrategyState(
                LocalDate.of(2025, 12, 1),
                "QQQ",
                new BigDecimal("600.00"),
                new BigDecimal("570.00"),
                new BigDecimal("5.0000"),
                new BigDecimal("5.0000"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.normal(),
                true,
                1);
        FakeSignalHistoricalDataProvider dataProvider = new FakeSignalHistoricalDataProvider(List.of(
                new BigDecimal("200.00"),
                new BigDecimal("250.00")));

        StrategyState result = service(new FakeStrategyStateRepository(oldQqqState), dataProvider)
                .runEod(LocalDate.of(2025, 12, 2), new BigDecimal("225.00"));

        assertThat(result.signalSymbol()).isEqualTo("QQQM");
        assertThat(result.ath()).isEqualByComparingTo("250.00");
        assertThat(result.drawdownPct()).isEqualByComparingTo("10.0000");
    }

    @Test
    void 커스텀임계값이StrategyState계산에반영된다() {
        TradingStrategyThresholdProps customThresholds = new TradingStrategyThresholdProps(
                new TradingStrategyThresholdProps.DrawdownProps(
                        new BigDecimal("10"),
                        new BigDecimal("20"),
                        new BigDecimal("30"),
                        new BigDecimal("40")),
                new TradingStrategyThresholdProps.RecoveryProps(
                        new BigDecimal("12"),
                        new BigDecimal("8")));
        StrategyState prev = state(
                new BigDecimal("100.00"),
                new BigDecimal("70.00"),
                new BigDecimal("30.0000"),
                new BigDecimal("30.0000"),
                DdBucket.FROM_25_TO_35,
                StrategyPhase.DRAWDOWN,
                WeightSet.of(40, 40, 20),
                true);

        StrategyState next = new StrategyStateEodService(
                new FakeStrategyStateRepository(prev),
                emptyDataProvider,
                strategyProps(),
                customThresholds
        ).runEod(LocalDate.of(2025, 12, 2), new BigDecimal("92.00"));

        assertThat(next.drawdownPct()).isEqualByComparingTo("8.0000");
        assertThat(next.phase()).isEqualTo(StrategyPhase.RECOVERY);
        assertThat(next.ddBucket()).isEqualTo(DdBucket.LESS_THAN_15);
    }

    private StrategyStateEodService service(
            FakeStrategyStateRepository repository,
            FakeSignalHistoricalDataProvider dataProvider
    ) {
        return new StrategyStateEodService(
                repository,
                dataProvider,
                strategyProps(),
                new TradingStrategyThresholdProps(null, null));
    }

    private TradingStrategyProps strategyProps() {
        return new TradingStrategyProps(null, "QQQM", null, null, null);
    }

    private StrategyState state(
            BigDecimal ath,
            BigDecimal close,
            BigDecimal drawdown,
            BigDecimal maxDrawdown,
            DdBucket bucket,
            StrategyPhase phase,
            WeightSet weights,
            boolean strategyOn
    ) {
        return new StrategyState(
                LocalDate.of(2025, 12, 1),
                "QQQM",
                ath,
                close,
                drawdown,
                maxDrawdown,
                bucket,
                phase,
                weights,
                strategyOn,
                1);
    }
}
