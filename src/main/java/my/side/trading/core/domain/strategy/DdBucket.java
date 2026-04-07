package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;

public enum DdBucket {
    LESS_THAN_15,
    FROM_15_TO_25,
    FROM_25_TO_35,
    FROM_35_TO_45,
    MORE_THAN_45;

    public static DdBucket from(BigDecimal ddPercent) {
        return from(ddPercent, DrawdownThresholds.defaults());
    }

    public static DdBucket from(BigDecimal ddPercent, DrawdownThresholds thresholds) {
        if (ddPercent.compareTo(thresholds.first()) < 0) {
            return LESS_THAN_15;
        } else if (ddPercent.compareTo(thresholds.second()) < 0) {
            return FROM_15_TO_25;
        } else if (ddPercent.compareTo(thresholds.third()) < 0) {
            return FROM_25_TO_35;
        } else if (ddPercent.compareTo(thresholds.fourth()) < 0) {
            return FROM_35_TO_45;
        } else {
            return MORE_THAN_45;
        }
    }
}
