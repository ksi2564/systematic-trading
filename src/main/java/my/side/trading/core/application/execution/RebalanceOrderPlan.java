package my.side.trading.core.application.execution;

import java.math.BigDecimal;
import java.util.List;

public record RebalanceOrderPlan(
        List<PlannedRebalanceOrder> orders,
        BigDecimal totalOrderNotional,
        BigDecimal estimatedRemainingCash,
        boolean quoteUnavailable
) {
    public RebalanceOrderPlan(
            List<PlannedRebalanceOrder> orders,
            BigDecimal totalOrderNotional,
            BigDecimal estimatedRemainingCash
    ) {
        this(orders, totalOrderNotional, estimatedRemainingCash, false);
    }

    public boolean hasRiskViolation() {
        return orders.stream().anyMatch(PlannedRebalanceOrder::blocked);
    }
}
