package my.side.trading.core.application.execution.pricing;

import my.side.trading.core.domain.execution.order.ExecutionOrderSide;

import java.math.BigDecimal;

/**
 * 주문가는 최우선 bid/ask를 기준으로 하고, 틱 단위의 최소 보정만 허용한다.
 */
public final class MarketLikePricingPolicy {
    private static final BigDecimal HUNDREDTH = new BigDecimal("0.01");

    private final BigDecimal tickSize;
    private final int initialBuyTicks;
    private final int initialSellTicks;
    private final int retryBuyTicks;
    private final int retrySellTicks;
    private final BigDecimal feePct;
    private final int maxAttempts;
    private final long retryWaitMs;

    public MarketLikePricingPolicy(
            BigDecimal tickSize,
            int initialBuyTicks,
            int initialSellTicks,
            int retryBuyTicks,
            int retrySellTicks,
            BigDecimal feePct,
            int maxAttempts,
            long retryWaitMs) {
        this.tickSize = tickSize;
        this.initialBuyTicks = initialBuyTicks;
        this.initialSellTicks = initialSellTicks;
        this.retryBuyTicks = retryBuyTicks;
        this.retrySellTicks = retrySellTicks;
        this.feePct = feePct;
        this.maxAttempts = maxAttempts;
        this.retryWaitMs = retryWaitMs;
    }

    public BigDecimal tickSize() {
        return tickSize;
    }

    public int priceTicksForAttempt(ExecutionOrderSide side, int attempt) {
        if (attempt < 1 || attempt > maxAttempts) {
            throw new IllegalArgumentException("attempt must be 1-" + maxAttempts);
        }
        int initialTicks = side == ExecutionOrderSide.BUY ? initialBuyTicks : initialSellTicks;
        int retryTicks = side == ExecutionOrderSide.BUY ? retryBuyTicks : retrySellTicks;
        return initialTicks + ((attempt - 1) * retryTicks);
    }

    public BigDecimal feeRate() {
        return feePct.multiply(HUNDREDTH);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long retryWaitMs() {
        return retryWaitMs;
    }
}
