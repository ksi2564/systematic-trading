package my.side.trading.core.domain.execution.plan;

import java.util.List;

public record RebalanceDecision(
        boolean shouldRebalance,
        RebalanceType type,
        String reason,
        List<OrderIntent> intents
) {
    public static RebalanceDecision no(String reason) {
        return new RebalanceDecision(false, null, reason, List.of());
    }

    public static RebalanceDecision yes(RebalanceType type, String reason, List<OrderIntent> intents) {
        return new RebalanceDecision(true, type, reason, intents);
    }
}
