package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.WeightSet;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RebalanceOrderPlanner {

    private final ExecutionOrderFactory orderFactory;
    private final ExecutionRiskLimitService riskLimitService;

    public RebalanceOrderPlan plan(
            LocalDate signalDate,
            RebalanceDecision decision,
            Portfolio portfolio
    ) {
        if (!decision.shouldRebalance()) {
            return new RebalanceOrderPlan(List.of(), BigDecimal.ZERO, defaultZero(portfolio.cash()));
        }

        WeightSet targetWeights = decision.targetWeights();
        if (targetWeights == null) {
            throw new IllegalArgumentException("targetWeights가 null입니다.");
        }

        List<PlannedRebalanceOrder> plannedOrders = new ArrayList<>();
        BigDecimal remainingCash = defaultZero(portfolio.cash());
        BigDecimal plannedNotional = BigDecimal.ZERO;

        for (OrderIntent intent : decision.intents()) {
            OrderAndCashDelta planned = orderFactory.fromTargetWeight(
                    intent.symbol(),
                    intent.side(),
                    targetWeights,
                    portfolio,
                    remainingCash).orElse(null);

            if (planned == null) {
                continue;
            }

            ExecutionOrder order = planned.order();
            BigDecimal orderNotional = riskLimitService.orderNotional(order);
            var violation = riskLimitService.plannedOrderViolation(signalDate, portfolio, order, plannedNotional)
                    .orElse(null);

            plannedOrders.add(new PlannedRebalanceOrder(
                    order,
                    planned.cashDelta(),
                    orderNotional,
                    violation));

            if (violation != null) {
                break;
            }

            remainingCash = remainingCash.add(planned.cashDelta());
            plannedNotional = plannedNotional.add(orderNotional);

            if (remainingCash.signum() < 0) {
                remainingCash = BigDecimal.ZERO;
            }
        }

        BigDecimal totalOrderNotional = plannedOrders.stream()
                .map(PlannedRebalanceOrder::notional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new RebalanceOrderPlan(plannedOrders, totalOrderNotional, remainingCash);
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
