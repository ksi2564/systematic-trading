package my.side.trading.core.domain.portfolio;

import java.math.BigDecimal;
import java.util.Optional;

public interface RealtimePriceProvider {

    Optional<RealtimeQuote> getQuote(String symbol);

    /**
     * 심볼별 최신 체결/호가 가격을 반환한다.
     * 웹소켓 핸들러에서 갱신한 캐시를 사용한다.
     */
    default Optional<BigDecimal> getLastPrice(String symbol) {
        return getQuote(symbol).map(RealtimeQuote::lastPrice);
    }

    default Optional<BigDecimal> getBestBidPrice(String symbol) {
        return getQuote(symbol).map(RealtimeQuote::bestBidPrice);
    }

    default Optional<BigDecimal> getBestAskPrice(String symbol) {
        return getQuote(symbol).map(RealtimeQuote::bestAskPrice);
    }
}
