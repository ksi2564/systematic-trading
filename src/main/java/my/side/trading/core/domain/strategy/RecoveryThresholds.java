package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;

public record RecoveryThresholds(
        BigDecimal activationMaxDrawdownPct,
        BigDecimal recoveryDrawdownPct
) {
    private static final BigDecimal DEFAULT_ACTIVATION_MAX_DRAWDOWN = new BigDecimal("15");
    private static final BigDecimal DEFAULT_RECOVERY_DRAWDOWN = new BigDecimal("10");

    public RecoveryThresholds {
        activationMaxDrawdownPct = defaultValue(activationMaxDrawdownPct, DEFAULT_ACTIVATION_MAX_DRAWDOWN);
        recoveryDrawdownPct = defaultValue(recoveryDrawdownPct, DEFAULT_RECOVERY_DRAWDOWN);

        if (activationMaxDrawdownPct.compareTo(BigDecimal.ZERO) <= 0
                || recoveryDrawdownPct.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("recovery thresholds must be zero or positive");
        }
        if (recoveryDrawdownPct.compareTo(activationMaxDrawdownPct) >= 0) {
            throw new IllegalArgumentException("recovery drawdown must be lower than activation max drawdown");
        }
    }

    public static RecoveryThresholds defaults() {
        return new RecoveryThresholds(null, null);
    }

    private static BigDecimal defaultValue(BigDecimal actual, BigDecimal fallback) {
        return actual == null ? fallback : actual;
    }
}
