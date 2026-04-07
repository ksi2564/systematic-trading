package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;

public record DrawdownThresholds(
        BigDecimal first,
        BigDecimal second,
        BigDecimal third,
        BigDecimal fourth
) {
    private static final BigDecimal DEFAULT_FIRST = new BigDecimal("15");
    private static final BigDecimal DEFAULT_SECOND = new BigDecimal("25");
    private static final BigDecimal DEFAULT_THIRD = new BigDecimal("35");
    private static final BigDecimal DEFAULT_FOURTH = new BigDecimal("45");

    public DrawdownThresholds {
        first = defaultValue(first, DEFAULT_FIRST);
        second = defaultValue(second, DEFAULT_SECOND);
        third = defaultValue(third, DEFAULT_THIRD);
        fourth = defaultValue(fourth, DEFAULT_FOURTH);

        if (first.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("drawdown first threshold must be positive");
        }
        if (first.compareTo(second) >= 0 || second.compareTo(third) >= 0 || third.compareTo(fourth) >= 0) {
            throw new IllegalArgumentException("drawdown thresholds must be strictly increasing");
        }
    }

    public static DrawdownThresholds defaults() {
        return new DrawdownThresholds(null, null, null, null);
    }

    private static BigDecimal defaultValue(BigDecimal actual, BigDecimal fallback) {
        return actual == null ? fallback : actual;
    }
}
