package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.OrderInquiry;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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
    private final OpsAlertPublisher opsAlertPublisher;

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
            log.error("job 실행에 실패했습니다: jobId={}", jobId, e);
            throw e;
        }
    }

    private boolean processOrder(ExecutionJob job, ExecutionOrder order, LocalDateTime now) {
        try {
            job.markOrderRequested(order.getId(), "Starting execution");

            ExecutionResult result = orderExecutor.executeWithRetry(order);
            result = applySlippageGuard(order, result);
            publishExecutionAlerts(job, order, result);

            if (result.isSuccess() || result.isPartial()) {
                job.acceptOrder(order.getId(), result.brokerOrderId(), orderMessage("Success", result), now);
            } else {
                job.rejectOrder(order.getId(), result.brokerOrderId(), orderMessage("Failed", result), now);
            }
            return result.isBlocked();
        } catch (Exception e) {
            log.error("주문 처리 중 오류가 발생했습니다: orderId={}", order.getId(), e);
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

    private void publishExecutionAlerts(ExecutionJob job, ExecutionOrder order, ExecutionResult result) {
        if (result.isPartial()) {
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("jobId", String.valueOf(job.getId()));
            details.put("orderId", String.valueOf(order.getId()));
            details.put("symbol", order.getSymbol());
            details.put("filledQty", String.valueOf(result.filledQty()));
            details.put("requestedQty", String.valueOf(order.getQuantity()));
            opsAlertPublisher.publish(new OpsAlert(
                    OpsAlertType.UNRESOLVED_ORDER,
                    OpsAlertSeverity.WARN,
                    "unresolved-order:" + job.getId() + ":" + order.getId(),
                    "Order ended with partial fill and needs attention",
                    details));
        }

        if (result.isBlocked() && result.blockReason() == ExecutionBlockReason.RISK_LIMIT_BREACH) {
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("jobId", String.valueOf(job.getId()));
            details.put("orderId", String.valueOf(order.getId()));
            details.put("symbol", order.getSymbol());
            details.put("detail", result.detailMessage() == null ? "-" : result.detailMessage());
            opsAlertPublisher.publish(new OpsAlert(
                    OpsAlertType.RISK_LIMIT_BREACH,
                    OpsAlertSeverity.ERROR,
                    "runtime-risk-limit:" + job.getId() + ":" + order.getId(),
                    "Execution blocked by runtime risk limit",
                    details));
        }
    }

    private void skipRemainingOrders(ExecutionJob job, Long processedOrderId, LocalDateTime now, String message) {
        job.getOrders().stream()
                .filter(order -> !order.isTerminal())
                .filter(order -> !order.getId().equals(processedOrderId))
                .forEach(order -> job.skipOrder(order.getId(), message, now));
    }
}
