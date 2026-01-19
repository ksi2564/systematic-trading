package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.order.FillResult;

import java.math.BigDecimal;

/**
 * 주문 실행 결과
 */
public record ExecutionResult(
        ResultType type,
        String brokerOrderId,
        long filledQty,
        BigDecimal filledAmount,
        String symbol) {
    public enum ResultType {
        SUCCESS, // 전량 체결
        PARTIAL, // 부분 체결 후 취소
        FAILED // 실패
    }

    public static ExecutionResult success(String brokerOrderId, FillResult fill) {
        return new ExecutionResult(ResultType.SUCCESS, brokerOrderId, fill.filledQty(), fill.filledAmount(), null);
    }

    public static ExecutionResult success(String brokerOrderId, long filledQty, BigDecimal filledAmount) {
        return new ExecutionResult(ResultType.SUCCESS, brokerOrderId, filledQty, filledAmount, null);
    }

    public static ExecutionResult partial(String brokerOrderId, FillResult fill) {
        return new ExecutionResult(ResultType.PARTIAL, brokerOrderId, fill.filledQty(), fill.filledAmount(), null);
    }

    public static ExecutionResult partial(String brokerOrderId, long filledQty, BigDecimal filledAmount) {
        return new ExecutionResult(ResultType.PARTIAL, brokerOrderId, filledQty, filledAmount, null);
    }

    public static ExecutionResult failed(String symbol) {
        return new ExecutionResult(ResultType.FAILED, null, 0, BigDecimal.ZERO, symbol);
    }

    public boolean isSuccess() {
        return type == ResultType.SUCCESS;
    }

    public boolean isPartial() {
        return type == ResultType.PARTIAL;
    }

    public boolean isFailed() {
        return type == ResultType.FAILED;
    }
}
