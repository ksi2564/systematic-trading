package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.order.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 실행기
 * - SELL 주문 먼저 실행 → 체결 확인
 * - BUY 주문 나중에 실행
 * - 재시도 정책: 10초 대기, 부분체결 시 미체결분 재시도, 최대 3회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionJobExecutor {

    private final ExecutionJobRepository jobRepository;
    private final RetryableOrderExecutor retryableExecutor;
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
            executeOrderWithRetry(job, order, now);
        }

        // Phase 2: BUY 주문 실행
        List<ExecutionOrder> buyOrders = job.getOrders().stream()
                .filter(o -> o.getSide() == ExecutionOrderSide.BUY)
                .filter(o -> !o.isTerminal())
                .toList();

        log.info("[EXEC] Phase 2: BUY 주문 {} 개 실행", buyOrders.size());
        for (ExecutionOrder order : buyOrders) {
            executeOrderWithRetry(job, order, now);
        }

        job.completeIfAllTerminal(now);
        return jobRepository.save(job);
    }

    private void executeOrderWithRetry(ExecutionJob job, ExecutionOrder order, LocalDateTime now) {
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
        job.markOrderRequested(order.getId(), "주문 요청 (재시도 정책 적용)");

        // 재시도 로직을 통한 주문 실행
        ExecutionResult result = retryableExecutor.executeWithRetry(order);

        if (result.isSuccess()) {
            job.acceptOrder(order.getId(), result.brokerOrderId(),
                    String.format("전량 체결: %d주", result.filledQty()), now);
        } else if (result.isPartial()) {
            job.acceptOrder(order.getId(), result.brokerOrderId(),
                    String.format("부분 체결: %d주 (일부 미체결)", result.filledQty()), now);
        } else {
            job.rejectOrder(order.getId(), result.brokerOrderId(),
                    "3회 재시도 실패: " + result.symbol(), now);
        }
    }
}
