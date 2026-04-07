package my.side.trading.core.domain.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyThresholdRuleTest {

    @Test
    void drawdownThresholds를바꾸면버킷판정도함께바뀐다() {
        DrawdownThresholds thresholds = new DrawdownThresholds(
                new BigDecimal("10"),
                new BigDecimal("20"),
                new BigDecimal("30"),
                new BigDecimal("40"));

        assertThat(DdBucket.from(new BigDecimal("19.5"), thresholds)).isEqualTo(DdBucket.FROM_15_TO_25);
        assertThat(WeightSet.drawDown(new BigDecimal("19.5"), thresholds)).isEqualTo(WeightSet.of(60, 30, 10));
    }

    @Test
    void recoveryThresholds를바꾸면phase판정도함께바뀐다() {
        RecoveryThresholds thresholds = new RecoveryThresholds(
                new BigDecimal("12"),
                new BigDecimal("8"));

        assertThat(StrategyPhase.from(new BigDecimal("11.9"), new BigDecimal("7.5"), thresholds))
                .isEqualTo(StrategyPhase.NORMAL);
        assertThat(StrategyPhase.from(new BigDecimal("12.0"), new BigDecimal("8.0"), thresholds))
                .isEqualTo(StrategyPhase.RECOVERY);
        assertThat(StrategyPhase.from(new BigDecimal("12.0"), new BigDecimal("9.0"), thresholds))
                .isEqualTo(StrategyPhase.DRAWDOWN);
    }
}
