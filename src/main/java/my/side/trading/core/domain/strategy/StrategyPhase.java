package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;

public enum StrategyPhase {
    NORMAL,
    DRAWDOWN,
    RECOVERY;

    private static final BigDecimal FIFTEEN = BigDecimal.valueOf(15);
    private static final BigDecimal TEN = BigDecimal.valueOf(10);

    /**
     * @param maxDdPercent 해당 ATH 이후 최악의 DD (%)
     * @param ddPercent    현재 DD (%)
     */
    public static StrategyPhase from(BigDecimal maxDdPercent, BigDecimal ddPercent) {
        if (maxDdPercent.compareTo(FIFTEEN) < 0) {
            return NORMAL;
        }
        return ddPercent.compareTo(TEN) <= 0 ? RECOVERY : DRAWDOWN;
    }
}
