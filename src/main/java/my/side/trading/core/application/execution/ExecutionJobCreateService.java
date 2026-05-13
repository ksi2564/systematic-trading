package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.WeightSet;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionJobCreateService {

    private final RebalanceOrderPlanner orderPlanner;
    private final ExecutionJobRepository jobRepository;
    private final OpsAlertPublisher opsAlertPublisher;

    public Optional<ExecutionJob> createJob(
            LocalDate signalDate,
            Instant executeAfter,
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

        RebalanceOrderPlan plan = orderPlanner.plan(signalDate, decision, portfolio);
        plan.orders().stream()
                .filter(PlannedRebalanceOrder::blocked)
                .findFirst()
                .ifPresent(blockedOrder -> {
                    publishRiskLimitAlert(signalDate, blockedOrder);
                    throw new ExecutionRiskLimitExceededException(blockedOrder.riskViolation());
                });

        var orders = plan.orders().stream()
                .map(PlannedRebalanceOrder::order)
                .toList();

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

    private void publishRiskLimitAlert(LocalDate signalDate, PlannedRebalanceOrder blockedOrder) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("signalDate", signalDate.toString());
        details.put("symbol", blockedOrder.order().getSymbol());
        details.put("side", blockedOrder.order().getSide().name());
        details.put("violation", blockedOrder.riskViolation().summary());
        opsAlertPublisher.publish(new OpsAlert(
                OpsAlertType.RISK_LIMIT_BREACH,
                OpsAlertSeverity.ERROR,
                "planned-risk-limit:" + signalDate + ":" + blockedOrder.riskViolation().type(),
                "Execution job creation blocked by risk limit",
                details));
    }
}
