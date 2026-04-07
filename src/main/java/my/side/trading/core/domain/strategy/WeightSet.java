package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record WeightSet(
        BigDecimal wQqq,
        BigDecimal wQld,
        BigDecimal wTqqq
) {
    public static WeightSet normal() {
        return of(100, 0, 0);
    }

    public static WeightSet recovery() {
        return of(70, 30, 0);
    }

    public static WeightSet drawDown(BigDecimal ddPercent) {
        return drawDown(ddPercent, DrawdownThresholds.defaults());
    }

    public static WeightSet drawDown(BigDecimal ddPercent, DrawdownThresholds thresholds) {
        switch (DdBucket.from(ddPercent, thresholds)) {
            case LESS_THAN_15:
                return of(100, 0, 0);
            case FROM_15_TO_25:
                return of(60, 30, 10);
            case FROM_25_TO_35:
                return of(40, 40, 20);
            case FROM_35_TO_45:
                return of(30, 30, 40);
            case MORE_THAN_45:
                return of(20, 20, 60);
        }
        throw new IllegalStateException("ddPercent=" + ddPercent);
    }

    public static WeightSet of(int qqq, int qld, int tqqq) {
        return new WeightSet(
                percent(qqq),
                percent(qld),
                percent(tqqq)
        );
    }

    private static BigDecimal percent(int value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.UNNECESSARY);
    }
}
