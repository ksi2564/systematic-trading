package my.side.trading.adapter.out.yahoo;

import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.yahoo.dto.YahooQuoteResponse;
import my.side.trading.core.application.port.out.MarketDataProvider;
import my.side.trading.core.infrastructure.config.TradingCircuitBreakerProps;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Yahoo Finance API를 통해 VIX 지수를 조회하는 서비스
 * MarketDataProvider 포트 인터페이스를 구현하여 core 계층에 추상화를 제공
 */
@Slf4j
@Service
public class YahooVixService implements MarketDataProvider {

    private static final String YAHOO_FINANCE_BASE_URL = "https://query1.finance.yahoo.com";
    private static final String VIX_SYMBOL = "^VIX";

    private final WebClient yahooWebClient;
    private final TradingCircuitBreakerProps props;

    public YahooVixService(TradingCircuitBreakerProps props) {
        this.props = props;
        this.yahooWebClient = WebClient.builder()
                .baseUrl(YAHOO_FINANCE_BASE_URL)
                .build();
    }

    /**
     * VIX 현재가 조회
     * 
     * @return VIX 현재가, 조회 실패 시 Optional.empty()
     */
    public Optional<BigDecimal> getVixPrice() {
        if (!props.isVixEnabled()) {
            log.debug("VIX filter is disabled");
            return Optional.empty();
        }

        try {
            YahooQuoteResponse response = yahooWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v8/finance/chart/{symbol}")
                            .build(VIX_SYMBOL))
                    .retrieve()
                    .bodyToMono(YahooQuoteResponse.class)
                    .block();

            if (response == null) {
                log.warn("Yahoo Finance VIX response is null");
                return Optional.empty();
            }

            BigDecimal vix = response.getCurrentPrice();
            if (vix == null) {
                log.warn("VIX price not found in response");
                return Optional.empty();
            }

            log.info("VIX current price: {}", vix);
            return Optional.of(vix);

        } catch (Exception e) {
            log.error("Failed to fetch VIX from Yahoo Finance", e);
            return Optional.empty();
        }
    }

    /**
     * QQQ 200일 종가 조회
     * 
     * @param days 조회할 일수 (기본 200)
     * @return 종가 리스트 (최신순)
     */
    public List<BigDecimal> getQqqHistoricalPrices(int days) {
        try {
            // range: 1y (1년), interval: 1d (일봉)
            YahooQuoteResponse response = yahooWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v8/finance/chart/{symbol}")
                            .queryParam("range", "1y")
                            .queryParam("interval", "1d")
                            .build("QQQ"))
                    .retrieve()
                    .bodyToMono(YahooQuoteResponse.class)
                    .block();

            if (response == null) {
                log.warn("Yahoo Finance QQQ response is null");
                return List.of();
            }

            List<BigDecimal> closes = response.getClosePrices();
            log.info("Fetched {} daily closes for QQQ", closes.size());

            // 최근 days일만 반환
            if (closes.size() > days) {
                return closes.subList(closes.size() - days, closes.size());
            }
            return closes;

        } catch (Exception e) {
            log.error("Failed to fetch QQQ historical prices from Yahoo Finance", e);
            return List.of();
        }
    }

    /**
     * QQQ 200일 이동평균 계산
     * 
     * @return 200MA, 계산 불가 시 Optional.empty()
     */
    public Optional<BigDecimal> getQqq200Ma() {
        int period = props.getMaPeriod();
        List<BigDecimal> prices = getQqqHistoricalPrices(period);

        if (prices.size() < period) {
            log.warn("Insufficient data for {}MA calculation: {} days available", period, prices.size());
            return Optional.empty();
        }

        BigDecimal sum = prices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ma = sum.divide(BigDecimal.valueOf(period), 4, java.math.RoundingMode.HALF_UP);

        log.info("QQQ {}MA: {}", period, ma);
        return Optional.of(ma);
    }
}
