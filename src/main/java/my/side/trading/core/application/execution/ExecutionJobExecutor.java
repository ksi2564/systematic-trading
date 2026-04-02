package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.OrderInquiry;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionJobExecutor {

    private final ExecutionJobRepository jobRepository;
    private final RetryableOrderExecutor orderExecutor;
    private final OrderInquiry orderInquiry;
    private final ExecutionGuard guard;
    private final ExecutionRiskLimitService riskLimitService;

    public ExecutionJob execute(Long jobId, LocalDateTime now) {
        return execute(jobId, now, ExecutionTriggerType.MANUAL);
    }

    public ExecutionJob execute(Long jobId, LocalDateTime now, ExecutionTriggerType triggerType) {
        guard.requireExecutionAllowed(triggerType);

        ExecutionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        try {
            job.start(now);
            jobRepository.save(job);

            List<ExecutionOrder> sortedOrders = job.getOrders().stream()
                    .sorted(Comparator.comparing((ExecutionOrder o) -> o.getSide() == ExecutionOrderSide.SELL ? 0 : 1)
                            .thenComparing(ExecutionOrder::getSymbol))
                    .toList();

            for (ExecutionOrder order : sortedOrders) {
                if (processOrder(job, order, now)) {
                    skipRemainingOrders(job, order.getId(), now, "Blocked by RISK_LIMIT_BREACH");
                    break;
                }
            }

            job.completeIfAllTerminal(now);
            return jobRepository.save(job);
        } catch (Exception e) {
            log.error("Job execution failed: jobId={}", jobId, e);
            throw e;
        }
    }

    private boolean processOrder(ExecutionJob job, ExecutionOrder order, LocalDateTime now) {
        try {
            job.markOrderRequested(order.getId(), "Starting execution");

            ExecutionResult result = orderExecutor.executeWithRetry(order);
            result = applySlippageGuard(order, result);

            if (result.isSuccess() || result.isPartial()) {
                job.acceptOrder(order.getId(), result.brokerOrderId(), orderMessage("Success", result), now);
            } else {
                job.rejectOrder(order.getId(), result.brokerOrderId(), orderMessage("Failed", result), now);
            }
            return result.isBlocked();
        } catch (Exception e) {
            log.error("Order processing error: orderId={}", order.getId(), e);
            job.rejectOrder(order.getId(), null, "Error: " + e.getMessage(), now);
            return false;
        }
    }

    private ExecutionResult applySlippageGuard(ExecutionOrder order, ExecutionResult result) {
        if (!result.isSuccess() && !result.isPartial()) {
            return result;
        }
        return riskLimitService.slippageViolation(order, result)
                .map(violation -> result.withBlock(ExecutionBlockReason.RISK_LIMIT_BREACH, violation.summary()))
                .orElse(result);
    }

    private String orderMessage(String defaultMessage, ExecutionResult result) {
        return result.detailMessage() == null || result.detailMessage().isBlank()
                ? defaultMessage
                : defaultMessage + " | " + result.detailMessage();
    }

    private void skipRemainingOrders(ExecutionJob job, Long processedOrderId, LocalDateTime now, String message) {
        job.getOrders().stream()
                .filter(order -> !order.isTerminal())
                .filter(order -> !order.getId().equals(processedOrderId))
                .forEach(order -> job.skipOrder(order.getId(), message, now));
    }
}
