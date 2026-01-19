package my.side.trading.core.domain.execution.order;

import java.math.BigDecimal;

/**
 * 체결 조회 결과
 */
public record FillResult(
        boolean fullyFilled,
        long filledQty,
        long unfilledQty,
        BigDecimal filledAmount) {
    public static FillResult full(long filledQty, BigDecimal amount) {
        return new FillResult(true, filledQty, 0, amount);
    }

    public static FillResult partial(long filledQty, long unfilledQty, BigDecimal amount) {
        return new FillResult(false, filledQty, unfilledQty, amount);
    }

    public static FillResult notFound() {
        return new FillResult(false, 0, 0, BigDecimal.ZERO);
    }
}
