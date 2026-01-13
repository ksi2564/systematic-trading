package my.side.trading.core.application.execution.pricing;

import java.math.BigDecimal;

/**
 * 입력은 "pct" (예: 0.5 == 0.5%) 형태로 받고,
 * 외부 제공은 decimal rate (예: 0.005)로만 제공한다.
 */
public final class MarketLikePricingPolicy {
    private static final BigDecimal HUNDREDTH = new BigDecimal("0.01");

    private final BigDecimal buyBufferPct;
    private final BigDecimal sellBufferPct;
    private final BigDecimal feePct;

    public MarketLikePricingPolicy(BigDecimal buyBufferPct, BigDecimal sellBufferPct, BigDecimal feePct) {
        this.buyBufferPct = buyBufferPct;
        this.sellBufferPct = sellBufferPct;
        this.feePct = feePct;
    }

    public BigDecimal buyBuffer() {
        return buyBufferPct.multiply(HUNDREDTH);
    }

    public BigDecimal sellBuffer() {
        return sellBufferPct.multiply(HUNDREDTH);
    }

    public BigDecimal feeRate() {
        return feePct.multiply(HUNDREDTH);
    }
}
