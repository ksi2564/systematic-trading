package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.order.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 실행기
 * - SELL 주문 먼저 실행 → 체결 대기
 * - BUY 주문 나중에 실행
 */
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

        // Phase 1: SELL 주문 먼저 실행
        List<ExecutionOrder> sellOrders = job.getOrders().stream()
                .filter(o -> o.getSide() == ExecutionOrderSide.SELL)
                .filter(o -> !o.isTerminal())
                .toList();

        log.info("[EXEC] Phase 1: SELL 주문 {} 개 실행", sellOrders.size());
        for (ExecutionOrder order : sellOrders) {
            executeOrder(job, order, now);
        }

        // Phase 2: BUY 주문 실행
        List<ExecutionOrder> buyOrders = job.getOrders().stream()
                .filter(o -> o.getSide() == ExecutionOrderSide.BUY)
                .filter(o -> !o.isTerminal())
                .toList();

        log.info("[EXEC] Phase 2: BUY 주문 {} 개 실행", buyOrders.size());
        for (ExecutionOrder order : buyOrders) {
            executeOrder(job, order, now);
        }

        job.completeIfAllTerminal(now);
        return jobRepository.save(job);
    }

    private void executeOrder(ExecutionJob job, ExecutionOrder order, LocalDateTime now) {
        if (order.getStatus() == ExecutionOrderStatus.REQUESTED) {
            // 재실행 시 재주문 금지
            OrderInquiryResult result = orderInquiry.confirm(order);
            if (result.found()) {
                job.acceptOrder(order.getId(), result.brokerOrderId(), result.message(), now);
            }
            return;
        }

        if (order.getStatus() != ExecutionOrderStatus.PLANNED) {
            return;
        }

        // PLANNED -> REQUESTED
        job.markOrderRequested(order.getId(), "주문 요청");

        BrokerOrderResult result = orderBroker.place(order);

        // 차단은 실패(REJECT)가 아니라 SKIPPED
        if (isBlocked(result)) {
            job.skipOrder(order.getId(), result.message(), now);
            return;
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

    private boolean isBlocked(BrokerOrderResult r) {
        return r != null && r.message() != null && r.message().startsWith("BLOCKED:");
    }
}
