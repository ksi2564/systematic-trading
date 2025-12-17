package my.side.trading.adapter.out.realtime;

import my.side.trading.core.domain.portfolio.RealtimePriceProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRealtimePriceProvider implements RealtimePriceProvider {

    private final Map<String, BigDecimal> lastPriceMap = new ConcurrentHashMap<>();

    @Override
    public Optional<BigDecimal> getLastPrice(String symbol) {
        return Optional.ofNullable(lastPriceMap.get(symbol));
    }

    // WebSocket 핸들러에서 가격 갱신
    public void updatePrice(String symbol, BigDecimal price) {
        lastPriceMap.put(symbol, price);
    }
}
