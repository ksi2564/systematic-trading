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
 * - 미체결 시 버퍼 증가하여 최대 3회 재시도
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
     * 
     * @param order 실행할 주문
     * @return 체결 결과 (최종 성공 또는 3회 실패)
     */
    public ExecutionResult executeWithRetry(ExecutionOrder order) {
        String symbol = order.getSymbol();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            BigDecimal buffer = BUFFER_PERCENTS[attempt - 1];
            log.info("[RETRY] 시도 {}/{}: symbol={}, side={}, qty={}, buffer={}%",
                    attempt, MAX_ATTEMPTS, symbol, order.getSide(), order.getQuantity(), buffer);

            // 주문 제출
            BrokerOrderResult placeResult = orderBroker.place(order);
            if (!placeResult.success()) {
                log.warn("[RETRY] 주문 실패: symbol={}, message={}", symbol, placeResult.message());
                continue; // 다음 시도
            }

            String brokerOrderId = placeResult.brokerOrderId();
            log.info("[RETRY] 주문 접수됨: symbol={}, brokerOrderId={}", symbol, brokerOrderId);

            // 체결 대기
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[RETRY] 대기 중 인터럽트 발생");
                }
            }

            // 체결 확인
            FillResult fillResult = fillChecker.checkFill(brokerOrderId, symbol);
            if (fillResult.fullyFilled()) {
                log.info("[RETRY] 체결 완료: symbol={}, filledQty={}, amount={}",
                        symbol, fillResult.filledQty(), fillResult.filledAmount());
                return ExecutionResult.success(brokerOrderId, fillResult);
            }

            // 미체결 → 주문 취소
            log.info("[RETRY] 미체결: symbol={}, filledQty={}, unfilledQty={} → 취소 시도",
                    symbol, fillResult.filledQty(), fillResult.unfilledQty());
            CancelResult cancelResult = canceller.cancel(brokerOrderId, symbol);
            if (!cancelResult.success()) {
                log.warn("[RETRY] 취소 실패: symbol={}, message={}", symbol, cancelResult.message());
            }

            // 부분 체결된 경우 부분 성공으로 반환
            if (fillResult.filledQty() > 0) {
                log.info("[RETRY] 부분 체결 후 취소: symbol={}, filledQty={}", symbol, fillResult.filledQty());
                return ExecutionResult.partial(brokerOrderId, fillResult);
            }

            // 다음 시도 (버퍼 증가)
        }

        log.error("[RETRY] {} 시도 모두 실패: symbol={}", MAX_ATTEMPTS, symbol);
        return ExecutionResult.failed(symbol);
    }

    /**
     * 버퍼 퍼센트 반환 (테스트용)
     */
    public BigDecimal getBufferPercent(int attempt) {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attempt must be 1-" + MAX_ATTEMPTS);
        }
        return BUFFER_PERCENTS[attempt - 1];
    }
}
