package my.side.trading.adapter.out.yahoo;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.strategy.SignalHistoricalDataProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Yahoo Finance를 통한 전략 판단 기준 ETF 과거 가격 제공 어댑터
 */
@Component
@RequiredArgsConstructor
public class YahooSignalHistoricalDataAdapter implements SignalHistoricalDataProvider {

    private final YahooVixService yahooVixService;

    @Override
    public List<BigDecimal> getHistoricalClosePrices(String symbol, int days) {
        return yahooVixService.getHistoricalPrices(symbol, days);
    }
}
