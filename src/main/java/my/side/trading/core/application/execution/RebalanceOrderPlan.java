package my.side.trading.core.application.execution;

import java.math.BigDecimal;
import java.util.List;

public record RebalanceOrderPlan(
        List<PlannedRebalanceOrder> orders,
        BigDecimal totalOrderNotional,
        BigDecimal estimatedRemainingCash
) {
    public boolean hasRiskViolation() {
        return orders.stream().anyMatch(PlannedRebalanceOrder::blocked);
    }
}
