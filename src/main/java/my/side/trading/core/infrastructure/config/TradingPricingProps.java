package my.side.trading.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "trading.pricing")
public record TradingPricingProps(
        BigDecimal tickSize,
        int initialBuyTicks,
        int initialSellTicks,
        int retryBuyTicks,
        int retrySellTicks,
        BigDecimal feePct,
        int maxAttempts,
        long retryWaitMs
) {
    public TradingPricingProps {
        tickSize = tickSize == null ? new BigDecimal("0.01") : tickSize;
        feePct = feePct == null ? new BigDecimal("0.25") : feePct;
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        retryWaitMs = retryWaitMs < 0 ? 2_000L : retryWaitMs;
    }
}
