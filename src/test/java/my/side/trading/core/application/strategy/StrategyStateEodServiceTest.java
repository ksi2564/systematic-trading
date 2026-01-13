package my.side.trading.core.application.strategy;

import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.testutil.FakeStrategyStateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyStateEodServiceTest {

    @Test
    void 전고점_돌파_시_전고점_갱신_및_DD_최대DD_초기화() {
        StrategyState prev = new StrategyState(
                LocalDate.of(2025, 12, 1),
                new BigDecimal("100.00"),
                new BigDecimal("95.00"),
                new BigDecimal("5.0000"),
                new BigDecimal("5.0000"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.normal(),
                true,
                1
        );

        FakeStrategyStateRepository repo = new FakeStrategyStateRepository(prev);
        StrategyStateEodService service = new StrategyStateEodService(repo);

        StrategyState next = service.runEod(LocalDate.of(2025, 12, 2), new BigDecimal("101.23"));

        assertThat(next.ath()).isEqualByComparingTo("101.23");
        assertThat(next.drawdownPct()).isEqualByComparingTo("0.0000");
        assertThat(next.maxDrawdownPctSinceAth()).isEqualByComparingTo("0.0000");
        assertThat(next.phase()).isEqualTo(StrategyPhase.NORMAL);
        assertThat(next.targetWeights()).isEqualTo(WeightSet.normal());
        assertThat(next.strategyOn()).isTrue();
    }

    @Test
    void 전고점_대비_하락_시_DD_최대DD_Phase_갱신() {
        StrategyState prev = new StrategyState(
                LocalDate.of(2025, 12, 1),
                new BigDecimal("100.00"),
                new BigDecimal("95.00"),
                new BigDecimal("5.0000"),
                new BigDecimal("5.0000"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.normal(),
                true,
                1
        );

        FakeStrategyStateRepository repo = new FakeStrategyStateRepository(prev);
        StrategyStateEodService service = new StrategyStateEodService(repo);

        // close=85이면 dd=15%
        StrategyState next = service.runEod(LocalDate.of(2025, 12, 2), new BigDecimal("85.00"));

        assertThat(next.ath()).isEqualByComparingTo("100.00");
        assertThat(next.drawdownPct()).isEqualByComparingTo("15.0000");
        assertThat(next.maxDrawdownPctSinceAth()).isEqualByComparingTo("15.0000");

        assertThat(next.phase()).isEqualTo(StrategyPhase.DRAWDOWN);
        assertThat(next.targetWeights()).isEqualTo(WeightSet.of(60, 30, 10));
        assertThat(next.strategyOn()).isTrue();
    }

    @Test
    void DD_10_15퍼_구간에서는_기존_목표비중_유지() {
        StrategyState prev = new StrategyState(
                LocalDate.of(2025, 12, 1),
                new BigDecimal("100.00"),
                new BigDecimal("82.00"),
                new BigDecimal("18.0000"),
                new BigDecimal("20.0000"),
                DdBucket.FROM_15_TO_25,
                StrategyPhase.DRAWDOWN,
                WeightSet.of(60, 30, 10),
                true,
                1
        );

        FakeStrategyStateRepository repo = new FakeStrategyStateRepository(prev);
        StrategyStateEodService service = new StrategyStateEodService(repo);

        // close=88 => dd=12%
        StrategyState next = service.runEod(LocalDate.of(2025, 12, 2), new BigDecimal("88.00"));

        assertThat(next.drawdownPct()).isEqualByComparingTo("12.0000");
        assertThat(next.phase()).isEqualTo(StrategyPhase.DRAWDOWN);
        // 10 < dd < 15 => 이전 비중 유지
        assertThat(next.targetWeights()).isEqualTo(prev.targetWeights());
        assertThat(next.strategyOn()).isTrue();
    }

    @Test
    void 최대DD_15퍼_이상이고_DD_10퍼_이내_회복_시_RECOVERY_Phase로_전환() {
        StrategyState prev = new StrategyState(
                LocalDate.of(2025, 12, 1),
                new BigDecimal("100.00"),
                new BigDecimal("82.00"),
                new BigDecimal("18.0000"),
                new BigDecimal("20.0000"),
                DdBucket.FROM_15_TO_25,
                StrategyPhase.DRAWDOWN,
                WeightSet.of(60, 30, 10),
                true,
                1
        );

        FakeStrategyStateRepository repo = new FakeStrategyStateRepository(prev);
        StrategyStateEodService service = new StrategyStateEodService(repo);

        // close=90 => dd=10%
        StrategyState next = service.runEod(LocalDate.of(2025, 12, 2), new BigDecimal("90.00"));

        assertThat(next.drawdownPct()).isEqualByComparingTo("10.0000");
        assertThat(next.phase()).isEqualTo(StrategyPhase.RECOVERY);
        assertThat(next.targetWeights()).isEqualTo(WeightSet.recovery());
        assertThat(next.strategyOn()).isTrue();
    }

    @Test
    void strategyOn은_EOD와_무관하게_이전_값_유지() {
        StrategyState prev = new StrategyState(
                LocalDate.of(2025, 12, 1),
                new BigDecimal("100.00"),
                new BigDecimal("95.00"),
                new BigDecimal("5.0000"),
                new BigDecimal("5.0000"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.normal(),
                false, // 운영 중지
                1
        );

        StrategyState next = new StrategyStateEodService(new FakeStrategyStateRepository(prev))
                .runEod(LocalDate.of(2025, 12, 2), new BigDecimal("85.00")); // drawdown 유발

        assertThat(next.phase()).isEqualTo(StrategyPhase.DRAWDOWN);
        assertThat(next.strategyOn()).isFalse();
    }

}
