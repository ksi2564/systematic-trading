package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.order.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionJobExecutor {

    private final ExecutionJobRepository jobRepository;
    private final RetryableOrderExecutor orderExecutor;
    private final OrderInquiry orderInquiry;
    private final ExecutionGuard guard;

    @Transactional
    public ExecutionJob execute(Long jobId, LocalDateTime now) {
        // 1. 실행 권한 체크 (Kill Switch 등)
        guard.requireExecutionAllowed();

        ExecutionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        try {
            job.start(now);
            jobRepository.save(job);

            // 2. 주문 정렬: SELL 먼저, 그 다음 BUY
            // (현금 확보를 위해 매도 먼저 실행)
            List<ExecutionOrder> sortedOrders = job.getOrders().stream()
                    .sorted(Comparator.comparing((ExecutionOrder o) -> o.getSide() == ExecutionOrderSide.SELL ? 0 : 1)
                            .thenComparing(ExecutionOrder::getSymbol))
                    .collect(Collectors.toList());

            for (ExecutionOrder order : sortedOrders) {
                processOrder(job, order, now);
            }

            job.completeIfAllTerminal(now);
            return jobRepository.save(job);
        } catch (Exception e) {
            log.error("Job execution failed: jobId={}", jobId, e);
            throw e; // 트랜잭션 롤백을 위해 예외 전파 (또는 비즈니스 예외로 래핑)
        }
    }

    private void processOrder(ExecutionJob job, ExecutionOrder order, LocalDateTime now) {
        try {
            job.markOrderRequested(order.getId(), "Starting execution");

            ExecutionResult result = orderExecutor.executeWithRetry(order);

            if (result.isSuccess() || result.isPartial()) {
                job.acceptOrder(order.getId(), result.brokerOrderId(), "Success", now);
            } else {
                job.rejectOrder(order.getId(), result.brokerOrderId(), "Failed", now);
            }
        } catch (Exception e) {
            log.error("Order processing error: orderId={}", order.getId(), e);
            job.rejectOrder(order.getId(), null, "Error: " + e.getMessage(), now);
        }
    }
}
