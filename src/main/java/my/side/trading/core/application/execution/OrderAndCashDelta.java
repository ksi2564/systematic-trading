package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.order.ExecutionOrder;

import java.math.BigDecimal;

/**
 * cashDelta:
 * - BUY: 음수(현금 유출)
 * - SELL: 양수(현금 유입)
 */
public record OrderAndCashDelta(
        ExecutionOrder order,
        BigDecimal cashDelta
) {
}
