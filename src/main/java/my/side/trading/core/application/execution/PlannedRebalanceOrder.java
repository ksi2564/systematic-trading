package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.order.ExecutionOrder;

import java.math.BigDecimal;

public record PlannedRebalanceOrder(
        ExecutionOrder order,
        BigDecimal estimatedCashDelta,
        BigDecimal notional,
        ExecutionRiskViolation riskViolation
) {
    public boolean blocked() {
        return riskViolation != null;
    }
}
