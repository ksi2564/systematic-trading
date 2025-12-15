package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;

public enum DdBucket {
    ZERO_TO_15,        // 0% ~ <15%
    FROM_15_TO_25,      // 15% ~ <25%
    FROM_25_TO_35,      // 25% ~ <35%
    FROM_35_TO_45,      // 35% ~ <45%
    MORE_THAN_45;       // >=45%

    /**
     * ddPercent: 0 ~ 100 사이의 값 (예: 15.23 == 15.23% DD)
     */
    public static DdBucket from(BigDecimal ddPercent) {
        if (ddPercent.compareTo(new BigDecimal("15")) < 0) {
            return ZERO_TO_15;
        } else if (ddPercent.compareTo(new BigDecimal("25")) < 0) {
            return FROM_15_TO_25;
        } else if (ddPercent.compareTo(new BigDecimal("35")) < 0) {
            return FROM_25_TO_35;
        } else if (ddPercent.compareTo(new BigDecimal("45")) < 0) {
            return FROM_35_TO_45;
        } else {
            return MORE_THAN_45;
        }
    }
}
