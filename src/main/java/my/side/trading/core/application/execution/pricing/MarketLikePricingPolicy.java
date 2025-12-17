package my.side.trading.core.application.execution.pricing;

import java.math.BigDecimal;

public record MarketLikePricingPolicy(
        BigDecimal buyBufferPct,
        BigDecimal sellBufferPct
) {
    private static final BigDecimal HUNDREDTH = new BigDecimal("0.01");

    public BigDecimal buyBuffer() {
        return buyBufferPct.multiply(HUNDREDTH);
    }

    public BigDecimal sellBuffer() {
        return sellBufferPct.multiply(HUNDREDTH);
    }
}
