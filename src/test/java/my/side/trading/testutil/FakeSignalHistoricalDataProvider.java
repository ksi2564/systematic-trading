package my.side.trading.testutil;

import my.side.trading.core.domain.strategy.SignalHistoricalDataProvider;

import java.math.BigDecimal;
import java.util.List;

/**
 * 테스트용 SignalHistoricalDataProvider 대역
 */
public class FakeSignalHistoricalDataProvider implements SignalHistoricalDataProvider {

    private final List<BigDecimal> historicalPrices;
    private String requestedSymbol;

    public FakeSignalHistoricalDataProvider(List<BigDecimal> historicalPrices) {
        this.historicalPrices = historicalPrices;
    }

    /**
     * 빈 리스트를 반환하는 기본 생성자이다.
     * 이 경우 initializeState는 현재가를 ATH로 사용한다.
     */
    public FakeSignalHistoricalDataProvider() {
        this.historicalPrices = List.of();
    }

    @Override
    public List<BigDecimal> getHistoricalClosePrices(String symbol, int days) {
        requestedSymbol = symbol;
        return historicalPrices;
    }

    public String requestedSymbol() {
        return requestedSymbol;
    }
}
