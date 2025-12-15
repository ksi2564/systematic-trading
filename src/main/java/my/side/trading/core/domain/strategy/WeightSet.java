package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record WeightSet(
        BigDecimal wQqq,
        BigDecimal wQld,
        BigDecimal wTqqq
) {
    // NORMAL 구간용: QQQ 100 / 0 / 0
    public static WeightSet normal() {
        return of(100, 0, 0);
    }

    // 회복 구간용: QQQ 70 / QLD 30 / 0
    public static WeightSet recovery() {
        return of(70, 30, 0);
    }

    // 정수(%)로 넣으면 소수 둘째 자리까지 BigDecimal로 만들어주는 팩토리
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
