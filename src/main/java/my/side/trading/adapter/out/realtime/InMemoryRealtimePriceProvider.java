package my.side.trading.adapter.out.realtime;

import my.side.trading.core.domain.portfolio.RealtimePriceProvider;
import my.side.trading.core.domain.portfolio.RealtimeQuote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRealtimePriceProvider implements RealtimePriceProvider {

    private final Map<String, RealtimeQuote> quoteMap = new ConcurrentHashMap<>();

    @Override
    public Optional<RealtimeQuote> getQuote(String symbol) {
        return Optional.ofNullable(quoteMap.get(symbol));
    }

    // WebSocket 핸들러에서 가격 갱신
    public void updatePrice(String symbol, BigDecimal price) {
        quoteMap.put(symbol, new RealtimeQuote(price, price, price));
    }

    public void updateQuote(String symbol, BigDecimal lastPrice, BigDecimal bestBidPrice, BigDecimal bestAskPrice) {
        quoteMap.put(symbol, new RealtimeQuote(lastPrice, bestBidPrice, bestAskPrice));
    }
}
