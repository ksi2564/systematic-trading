package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;
import java.util.List;

/**
 * 전략 판단 기준 ETF의 과거 가격 데이터 제공자(도메인 포트)
 * 애플리케이션 계층이 외부 API에 직접 의존하지 않도록 추상화한다.
 */
public interface SignalHistoricalDataProvider {

    /**
     * 전략 판단 기준 ETF의 과거 종가를 조회한다.
     *
     * @param symbol 조회할 ETF 심볼
     * @param days 조회할 일수
     * @return 종가 리스트 (과거순 -> 최신순)
     */
    List<BigDecimal> getHistoricalClosePrices(String symbol, int days);
}
