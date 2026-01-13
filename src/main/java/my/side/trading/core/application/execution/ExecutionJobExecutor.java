package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.order.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionJobExecutor {

    private final ExecutionJobRepository jobRepository;
    private final OrderBroker orderBroker;
    private final OrderInquiry orderInquiry;
    private final ExecutionGuard guard;

    public ExecutionJob execute(Long jobId, LocalDateTime now) {
        ExecutionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("execution job not found: " + jobId));

        if (!guard.isExecutionEnabled()) {
            log.info("[EXEC] Execution disabled. skip execute jobId={}, signalDate={}, status={}",
                    job.getId(), job.getSignalDate(), job.getStatus());
            return job;
        }

        if (guard.isKillSwitchOn()) {
            log.warn("[EXEC] kill switch ON -> skip execute jobId={}, signalDate={}, status={}",
                    job.getId(), job.getSignalDate(), job.getStatus());
            return job;
        }

        if (job.getStatus() == ExecutionStatus.PENDING) {
            job.start(now);
        }
        if (job.getStatus() != ExecutionStatus.RUNNING) {
            throw new IllegalStateException("RUNNING 상태에서만 실행 가능: " + job.getStatus());
        }

        for (ExecutionOrder order : job.getOrders()) {
            if (order.isTerminal())
                continue;

            if (order.getStatus() == ExecutionOrderStatus.REQUESTED) {
                // 재실행 시 재주문 금지
                OrderInquiryResult result = orderInquiry.confirm(order);
                if (result.found()) {
                    job.acceptOrder(order.getId(), result.brokerOrderId(), result.message(), now);
                }
                continue;
            }

            if (order.getStatus() != ExecutionOrderStatus.PLANNED)
                continue;

            // PLANNED -> REQUESTED
            job.markOrderRequested(order.getId(), "주문 요청");

            BrokerOrderResult result = orderBroker.place(order);

            // 차단은 실패(REJECT)가 아니라 SKIPPED
            if (isBlocked(result)) {
                job.skipOrder(order.getId(), result.message(), now);
                continue;
            }

            if (result.success()) {
                if (result.brokerOrderId() == null || result.brokerOrderId().isBlank()) {
                    job.remarkOrderRequested(order.getId(),
                            "broker success but missing orderId. msg=" + result.message());
                    OrderInquiryResult inquiryResult = orderInquiry.confirm(order);
                    if (inquiryResult.found()) {
                        job.acceptOrder(order.getId(), inquiryResult.brokerOrderId(), inquiryResult.message(), now);
                    }
                } else {
                    job.acceptOrder(order.getId(), result.brokerOrderId(), result.message(), now);
                }
            } else {
                job.rejectOrder(order.getId(), result.brokerOrderId(), result.message(), now);
            }
        }

        job.completeIfAllTerminal(now);
        return jobRepository.save(job);
    }

    private boolean isBlocked(BrokerOrderResult r) {
        return r != null && r.message() != null && r.message().startsWith("BLOCKED:");
    }
}
