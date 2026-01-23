package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.order.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 재시도 정책을 포함한 주문 실행기
 * - 10초 체결 대기
 * - 부분 체결 시 미체결 수량만 재시도
 * - 최대 3회 시도, 버퍼 점진적 증가 (0.3% → 0.5% → 0.8%)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryableOrderExecutor {

    private static final int MAX_ATTEMPTS = 3;
    private static final long DEFAULT_WAIT_MS = 10_000; // 10초
    private static final BigDecimal[] BUFFER_PERCENTS = {
            new BigDecimal("0.3"),
            new BigDecimal("0.5"),
            new BigDecimal("0.8")
    };

    private final OrderBroker orderBroker;
    private final OrderFillChecker fillChecker;
    private final OrderCanceller canceller;

    @Setter
    private long waitMs = DEFAULT_WAIT_MS;

    /**
     * 재시도 로직을 포함한 주문 실행
     * - 부분 체결 시: 미체결 수량만 다음 시도에서 재주문
     * - 체결 수량 누적하여 최종 결과 반환
     *
     * @param order 실행할 주문
     * @return 체결 결과 (총 체결 수량 포함)
     */
    public ExecutionResult executeWithRetry(ExecutionOrder order) {
        String symbol = order.getSymbol();
        long originalQty = order.getQuantity();
        long totalFilledQty = 0;
        BigDecimal totalFilledAmount = BigDecimal.ZERO;
        long remainingQty = originalQty;
        String lastBrokerOrderId = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS && remainingQty > 0; attempt++) {
            BigDecimal buffer = BUFFER_PERCENTS[attempt - 1];
            log.info("[RETRY] 시도 {}/{}: symbol={}, side={}, remainingQty={}, buffer={}%",
                    attempt, MAX_ATTEMPTS, symbol, order.getSide(), remainingQty, buffer);

            // 주문 제출 (미체결 수량만큼)
            ExecutionOrder retryOrder = createRetryOrder(order, remainingQty);
            BrokerOrderResult placeResult = orderBroker.place(retryOrder);

            if (!placeResult.success()) {
                log.warn("[RETRY] 주문 실패: symbol={}, message={}", symbol, placeResult.message());
                continue; // 다음 시도
            }

            String brokerOrderId = placeResult.brokerOrderId();
            lastBrokerOrderId = brokerOrderId;
            log.info("[RETRY] 주문 접수됨: symbol={}, brokerOrderId={}, qty={}",
                    symbol, brokerOrderId, remainingQty);

            // 체결 대기
            waitForFill();

            // 체결 확인
            FillResult fillResult = fillChecker.checkFill(brokerOrderId, symbol);
            long filledQty = fillResult.filledQty();
            long unfilledQty = fillResult.unfilledQty();

            if (filledQty > 0) {
                totalFilledQty += filledQty;
                totalFilledAmount = totalFilledAmount.add(fillResult.filledAmount());
                remainingQty -= filledQty;

                log.info("[RETRY] 체결: symbol={}, filledQty={}, totalFilledQty={}, remainingQty={}",
                        symbol, filledQty, totalFilledQty, remainingQty);
            }

            // 전량 체결 완료
            if (fillResult.fullyFilled() || remainingQty <= 0) {
                log.info("[RETRY] 전량 체결 완료: symbol={}, totalFilledQty={}", symbol, totalFilledQty);
                return ExecutionResult.success(lastBrokerOrderId, totalFilledQty, totalFilledAmount);
            }

            // 미체결분 취소 후 재시도
            if (unfilledQty > 0) {
                log.info("[RETRY] 미체결분 취소 시도: symbol={}, unfilledQty={}", symbol, unfilledQty);
                CancelResult cancelResult = canceller.cancel(brokerOrderId, symbol);
                if (!cancelResult.success()) {
                    log.warn("[RETRY] 취소 실패: symbol={}, message={}", symbol, cancelResult.message());
                }
            }
        }

        // 최종 결과 반환
        if (totalFilledQty > 0) {
            if (totalFilledQty >= originalQty) {
                log.info("[RETRY] 전량 체결: symbol={}, totalFilledQty={}", symbol, totalFilledQty);
                return ExecutionResult.success(lastBrokerOrderId, totalFilledQty, totalFilledAmount);
            } else {
                log.info("[RETRY] 부분 체결 종료: symbol={}, totalFilledQty={}/{}",
                        symbol, totalFilledQty, originalQty);
                return ExecutionResult.partial(lastBrokerOrderId, totalFilledQty, totalFilledAmount);
            }
        }

        log.error("[RETRY] {} 시도 모두 실패: symbol={}", MAX_ATTEMPTS, symbol);
        return ExecutionResult.failed(symbol);
    }

    private ExecutionOrder createRetryOrder(ExecutionOrder original, long newQty) {
        return ExecutionOrder.rehydrate(
                original.getId(),
                original.getSymbol(),
                original.getSide(),
                newQty,
                original.getRefPrice(),
                original.getLimitPrice(),
                ExecutionOrderStatus.PLANNED,
                null,
                null);
    }

    private void waitForFill() {
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[RETRY] 대기 중 인터럽트 발생");
            }
        }
    }

    public BigDecimal getBufferPercent(int attempt) {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attempt must be 1-" + MAX_ATTEMPTS);
        }
        return BUFFER_PERCENTS[attempt - 1];
    }
}
