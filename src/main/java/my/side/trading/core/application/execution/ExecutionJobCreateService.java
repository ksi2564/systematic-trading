package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.WeightSet;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionJobCreateService {

    private final ExecutionOrderFactory orderFactory;
    private final ExecutionJobRepository jobRepository;
    private final ExecutionRiskLimitService riskLimitService;

    public Optional<ExecutionJob> createJob(
            LocalDate signalDate,
            LocalDateTime executeAfter,
            RebalanceDecision decision,
            Portfolio portfolio) {
        if (!decision.shouldRebalance()) {
            throw new IllegalArgumentException("shouldRebalance=false decision로 job 생성 불가");
        }

        WeightSet targetWeights = decision.targetWeights();
        if (targetWeights == null) {
            throw new IllegalArgumentException("targetWeights가 null입니다.");
        }

        if (jobRepository.findBySignalDate(signalDate).isPresent()) {
            log.warn("job already exists: signalDate={}", signalDate);
            return Optional.empty();
        }

        List<ExecutionOrder> orders = new ArrayList<>();
        BigDecimal remainingCash = portfolio.cash();
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

            riskLimitService.validatePlannedOrder(signalDate, portfolio, planned.order(), plannedNotional);

            orders.add(planned.order());
            remainingCash = remainingCash.add(planned.cashDelta());
            plannedNotional = plannedNotional.add(riskLimitService.orderNotional(planned.order()));

            if (remainingCash.signum() < 0) {
                log.warn("remainingCash below zero; clamp to zero. symbol={}, side={}, qty={}, limitPrice={}, cashDelta={}, cashBefore={}",
                        planned.order().getSymbol(),
                        planned.order().getSide(),
                        planned.order().getQuantity(),
                        planned.order().getLimitPrice(),
                        planned.cashDelta(),
                        remainingCash.subtract(planned.cashDelta()));
                remainingCash = BigDecimal.ZERO;
            }

            log.info("planned order: sym={}, side={}, qty={}, limit={}, cashDelta={}, remainingCash={}",
                    planned.order().getSymbol(),
                    planned.order().getSide(),
                    planned.order().getQuantity(),
                    planned.order().getLimitPrice(),
                    planned.cashDelta(),
                    remainingCash);
        }

        if (orders.isEmpty()) {
            log.info("skip job creation: no executable orders. intents={}, reason={}",
                    decision.intents().size(), decision.reason());
            return Optional.empty();
        }

        ExecutionJob job = ExecutionJob.create(signalDate, executeAfter, orders);
        ExecutionJob saved = jobRepository.save(job);

        log.info("job created: jobId={}, orders={}", saved.getId(), orders.size());
        return Optional.of(saved);
    }
}
