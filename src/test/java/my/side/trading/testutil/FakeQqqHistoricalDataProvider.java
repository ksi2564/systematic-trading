package my.side.trading.testutil;

import my.side.trading.core.domain.strategy.QqqHistoricalDataProvider;

import java.math.BigDecimal;
import java.util.List;

/**
 * 테스트용 Fake QqqHistoricalDataProvider
 */
public class FakeQqqHistoricalDataProvider implements QqqHistoricalDataProvider {

    private final List<BigDecimal> historicalPrices;

    public FakeQqqHistoricalDataProvider(List<BigDecimal> historicalPrices) {
        this.historicalPrices = historicalPrices;
    }

    /**
     * 빈 리스트를 반환하는 기본 생성자 (기존 테스트 호환용)
     * 이 경우 initializeState는 현재가를 ATH로 사용
     */
    public FakeQqqHistoricalDataProvider() {
        this.historicalPrices = List.of();
    }

    @Override
    public List<BigDecimal> getHistoricalClosePrices(int days) {
        return historicalPrices;
    }
}
