package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;

public enum StrategyPhase {
    NORMAL,
    DRAWDOWN,
    RECOVERY;

    public static StrategyPhase from(BigDecimal maxDdPercent, BigDecimal ddPercent) {
        return from(maxDdPercent, ddPercent, RecoveryThresholds.defaults());
    }

    public static StrategyPhase from(
            BigDecimal maxDdPercent,
            BigDecimal ddPercent,
            RecoveryThresholds thresholds
    ) {
        if (maxDdPercent.compareTo(thresholds.activationMaxDrawdownPct()) < 0) {
            return NORMAL;
        }
        return ddPercent.compareTo(thresholds.recoveryDrawdownPct()) <= 0 ? RECOVERY : DRAWDOWN;
    }
}
