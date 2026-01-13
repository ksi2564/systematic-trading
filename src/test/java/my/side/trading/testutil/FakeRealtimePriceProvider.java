package my.side.trading.testutil;

import my.side.trading.core.domain.portfolio.RealtimePriceProvider;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public final class FakeRealtimePriceProvider implements RealtimePriceProvider {

    private final Map<String, BigDecimal> prices;

    public FakeRealtimePriceProvider(Map<String, BigDecimal> prices) {
        this.prices = prices;
    }

    @Override
    public Optional<BigDecimal> getLastPrice(String symbol) {
        return Optional.ofNullable(prices.get(symbol));
    }
}
