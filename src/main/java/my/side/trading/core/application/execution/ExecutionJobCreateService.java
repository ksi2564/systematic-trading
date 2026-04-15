package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.WeightSet;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
    private final OpsAlertPublisher opsAlertPublisher;

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
            log.warn("이미 생성된 job이 있습니다: signalDate={}", signalDate);
            opsAlertPublisher.publish(new OpsAlert(
                    OpsAlertType.DUPLICATE_SIGNAL_JOB_DETECTED,
                    OpsAlertSeverity.WARN,
                    "duplicate-signal-job:" + signalDate,
                    "Duplicate signalDate job creation attempt detected",
                    java.util.Map.of("signalDate", signalDate.toString())));
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

            try {
                riskLimitService.validatePlannedOrder(signalDate, portfolio, planned.order(), plannedNotional);
            } catch (ExecutionRiskLimitExceededException e) {
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("signalDate", signalDate.toString());
                details.put("symbol", planned.order().getSymbol());
                details.put("side", planned.order().getSide().name());
                details.put("violation", e.getViolation().summary());
                opsAlertPublisher.publish(new OpsAlert(
                        OpsAlertType.RISK_LIMIT_BREACH,
                        OpsAlertSeverity.ERROR,
                        "planned-risk-limit:" + signalDate + ":" + e.getViolation().type(),
                        "Execution job creation blocked by risk limit",
                        details));
                throw e;
            }

            orders.add(planned.order());
            remainingCash = remainingCash.add(planned.cashDelta());
            plannedNotional = plannedNotional.add(riskLimitService.orderNotional(planned.order()));

            if (remainingCash.signum() < 0) {
                log.warn("remainingCash가 0 미만이라 0으로 보정합니다. symbol={}, side={}, qty={}, limitPrice={}, cashDelta={}, cashBefore={}",
                        planned.order().getSymbol(),
                        planned.order().getSide(),
                        planned.order().getQuantity(),
                        planned.order().getLimitPrice(),
                        planned.cashDelta(),
                        remainingCash.subtract(planned.cashDelta()));
                remainingCash = BigDecimal.ZERO;
            }

            log.info("계획 주문을 생성했습니다: sym={}, side={}, qty={}, limit={}, cashDelta={}, remainingCash={}",
                    planned.order().getSymbol(),
                    planned.order().getSide(),
                    planned.order().getQuantity(),
                    planned.order().getLimitPrice(),
                    planned.cashDelta(),
                    remainingCash);
        }

        if (orders.isEmpty()) {
            log.info("실행 가능한 주문이 없어 job 생성을 건너뜁니다. intents={}, reason={}",
                    decision.intents().size(), decision.reason());
            return Optional.empty();
        }

        ExecutionJob job = ExecutionJob.create(signalDate, executeAfter, orders);
        ExecutionJob saved = jobRepository.save(job);

        log.info("job을 생성했습니다: jobId={}, orders={}", saved.getId(), orders.size());
        return Optional.of(saved);
    }
}
