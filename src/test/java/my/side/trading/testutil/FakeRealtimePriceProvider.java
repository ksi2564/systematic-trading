package my.side.trading.testutil;

import my.side.trading.core.domain.portfolio.RealtimePriceProvider;
import my.side.trading.core.domain.portfolio.RealtimeQuote;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class FakeRealtimePriceProvider implements RealtimePriceProvider {

    private final Map<String, RealtimeQuote> quotes = new ConcurrentHashMap<>();

    protected FakeRealtimePriceProvider() {
    }

    @Override
    public Optional<RealtimeQuote> getQuote(String symbol) {
        return Optional.ofNullable(quotes.get(symbol));
    }

    public static FakeRealtimePriceProvider withLastPrices(Map<String, BigDecimal> prices) {
        FakeRealtimePriceProvider provider = new FakeRealtimePriceProvider();
        prices.forEach((symbol, price) -> provider.updateQuote(symbol, price, price, price));
        return provider;
    }

    public void updateQuote(String symbol, BigDecimal lastPrice, BigDecimal bestBidPrice, BigDecimal bestAskPrice) {
        quotes.put(symbol, new RealtimeQuote(lastPrice, bestBidPrice, bestAskPrice));
    }
}
