package my.side.trading.core.application.execution;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.domain.execution.order.BrokerOrderResult;
import my.side.trading.core.domain.execution.order.CancelResult;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.FillResult;
import my.side.trading.core.domain.execution.order.OrderBroker;
import my.side.trading.core.domain.execution.order.OrderCanceller;
import my.side.trading.core.domain.execution.order.OrderFillChecker;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class RetryableOrderExecutor {

    private final OrderBroker orderBroker;
    private final OrderFillChecker fillChecker;
    private final OrderCanceller canceller;
    private final ExecutionOrderFactory orderFactory;
    private final MarketLikePricingPolicy pricingPolicy;
    private final ExecutionRiskLimitService riskLimitService;

    @Setter
    private long waitMs;

    public RetryableOrderExecutor(
            OrderBroker orderBroker,
            OrderFillChecker fillChecker,
            OrderCanceller canceller,
            ExecutionOrderFactory orderFactory,
            MarketLikePricingPolicy pricingPolicy,
            ExecutionRiskLimitService riskLimitService) {
        this.orderBroker = orderBroker;
        this.fillChecker = fillChecker;
        this.canceller = canceller;
        this.orderFactory = orderFactory;
        this.pricingPolicy = pricingPolicy;
        this.riskLimitService = riskLimitService;
        this.waitMs = pricingPolicy.retryWaitMs();
    }

    public ExecutionResult executeWithRetry(ExecutionOrder order) {
        String symbol = order.getSymbol();
        long originalQty = order.getQuantity();
        long totalFilledQty = 0;
        BigDecimal totalFilledAmount = BigDecimal.ZERO;
        long remainingQty = originalQty;
        String lastBrokerOrderId = null;
        int maxAttempts = pricingPolicy.maxAttempts();
        BigDecimal cumulativeExposure = BigDecimal.ZERO;

        for (int attempt = 1; attempt <= maxAttempts && remainingQty > 0; attempt++) {
            ExecutionOrder retryOrder = orderFactory.repriceForRetry(order, remainingQty, attempt);
            BigDecimal projectedExposure = cumulativeExposure.add(riskLimitService.orderNotional(retryOrder));
            var retryExposureViolation = riskLimitService.retryExposureViolation(projectedExposure);
            if (retryExposureViolation.isPresent()) {
                String detail = retryExposureViolation.get().summary();
                log.warn("[RETRY] 재시도 노출 한도를 초과했습니다: symbol={}, detail={}", symbol, detail);
                return currentResultOnBlock(symbol, totalFilledQty, totalFilledAmount, lastBrokerOrderId, detail);
            }

            log.info("[RETRY] 시도 {}/{}: symbol={}, side={}, remainingQty={}, ticks={}, refPrice={}, limitPrice={}",
                    attempt,
                    maxAttempts,
                    symbol,
                    order.getSide(),
                    remainingQty,
                    pricingPolicy.priceTicksForAttempt(order.getSide(), attempt),
                    retryOrder.getRefPrice(),
                    retryOrder.getLimitPrice());

            BrokerOrderResult placeResult = orderBroker.place(retryOrder);
            cumulativeExposure = projectedExposure;

            if (!placeResult.success()) {
                log.warn("[RETRY] 주문 실패: symbol={}, message={}", symbol, placeResult.message());
                continue;
            }

            String brokerOrderId = placeResult.brokerOrderId();
            lastBrokerOrderId = brokerOrderId;
            log.info("[RETRY] 주문 접수: symbol={}, brokerOrderId={}, qty={}", symbol, brokerOrderId, remainingQty);

            waitForFill();

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

            if (fillResult.fullyFilled() || remainingQty <= 0) {
                log.info("[RETRY] 완전 체결 종료: symbol={}, totalFilledQty={}", symbol, totalFilledQty);
                return ExecutionResult.success(lastBrokerOrderId, totalFilledQty, totalFilledAmount);
            }

            if (unfilledQty > 0) {
                log.info("[RETRY] 미체결분 취소 시도: symbol={}, unfilledQty={}", symbol, unfilledQty);
                CancelResult cancelResult = canceller.cancel(brokerOrderId, symbol);
                if (!cancelResult.success()) {
                    log.warn("[RETRY] 취소 실패: symbol={}, message={}", symbol, cancelResult.message());
                }
            }
        }

        if (totalFilledQty > 0) {
            if (totalFilledQty >= originalQty) {
                log.info("[RETRY] 완전 체결: symbol={}, totalFilledQty={}", symbol, totalFilledQty);
                return ExecutionResult.success(lastBrokerOrderId, totalFilledQty, totalFilledAmount);
            }
            log.info("[RETRY] 부분 체결 종료: symbol={}, totalFilledQty={}/{}", symbol, totalFilledQty, originalQty);
            return ExecutionResult.partial(lastBrokerOrderId, totalFilledQty, totalFilledAmount);
        }

        log.error("[RETRY] {}회 시도 모두 실패: symbol={}", maxAttempts, symbol);
        return ExecutionResult.failed(symbol);
    }

    private ExecutionResult currentResultOnBlock(
            String symbol,
            long totalFilledQty,
            BigDecimal totalFilledAmount,
            String lastBrokerOrderId,
            String detail) {
        ExecutionResult base = totalFilledQty > 0
                ? ExecutionResult.partial(lastBrokerOrderId, totalFilledQty, totalFilledAmount)
                : ExecutionResult.failed(symbol);
        return base.withBlock(ExecutionBlockReason.RISK_LIMIT_BREACH, detail);
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

    public int getRetryTickOffset(int attempt, ExecutionOrderSide side) {
        return pricingPolicy.priceTicksForAttempt(side, attempt);
    }
}
