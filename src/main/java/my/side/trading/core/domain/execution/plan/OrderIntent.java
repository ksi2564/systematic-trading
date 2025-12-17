package my.side.trading.core.domain.execution.plan;

import my.side.trading.core.domain.execution.order.ExecutionOrderSide;

import java.math.BigDecimal;

public record OrderIntent(
        String symbol,
        ExecutionOrderSide side,
        BigDecimal notionalUsd,  // 금액(USD) 기준
        String reason
) {
}
