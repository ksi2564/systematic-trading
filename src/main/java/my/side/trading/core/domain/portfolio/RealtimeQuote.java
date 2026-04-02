package my.side.trading.core.domain.portfolio;

import java.math.BigDecimal;

public record RealtimeQuote(
        BigDecimal lastPrice,
        BigDecimal bestBidPrice,
        BigDecimal bestAskPrice
) {
}
