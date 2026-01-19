package my.side.trading.adapter.out.yahoo;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.strategy.QqqHistoricalDataProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Yahoo Finance를 통한 QQQ 과거 가격 제공 어댑터
 */
@Component
@RequiredArgsConstructor
public class YahooQqqHistoricalDataAdapter implements QqqHistoricalDataProvider {

    private final YahooVixService yahooVixService;

    @Override
    public List<BigDecimal> getHistoricalClosePrices(int days) {
        return yahooVixService.getQqqHistoricalPrices(days);
    }
}
