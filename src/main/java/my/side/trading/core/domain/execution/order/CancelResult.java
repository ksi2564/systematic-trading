package my.side.trading.core.domain.execution.order;

/**
 * 주문 취소 결과
 */
public record CancelResult(
        boolean success,
        String message) {
    public static CancelResult success(String message) {
        return new CancelResult(true, message);
    }

    public static CancelResult failure(String message) {
        return new CancelResult(false, message);
    }
}
