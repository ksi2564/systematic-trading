package my.side.trading.core.domain.execution.plan;

import my.side.trading.core.domain.strategy.WeightSet;

import java.util.List;

/**
 * 리밸런싱 판단 결과
 * targetWeights: 주문 시점에 실시간 가격으로 수량을 계산할 목표 비중
 * intents: BUY/SELL 주문 방향 정보
 */
public record RebalanceDecision(
        boolean shouldRebalance,
        RebalanceType type,
        String reason,
        WeightSet targetWeights,
        List<OrderIntent> intents) {
    public static RebalanceDecision no(String reason) {
        return new RebalanceDecision(false, null, reason, null, List.of());
    }

    public static RebalanceDecision yes(RebalanceType type, String reason, WeightSet targetWeights,
            List<OrderIntent> intents) {
        return new RebalanceDecision(true, type, reason, targetWeights, intents);
    }
}
