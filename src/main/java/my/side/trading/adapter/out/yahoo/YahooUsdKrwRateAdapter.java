package my.side.trading.adapter.out.yahoo;

import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.yahoo.dto.YahooQuoteResponse;
import my.side.trading.core.application.port.out.CurrentFxRateProvider;
import my.side.trading.core.application.port.out.FxRateReader;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Primary
@Component
public class YahooUsdKrwRateAdapter implements FxRateReader, CurrentFxRateProvider {

    private static final String YAHOO_FINANCE_BASE_URL = "https://query1.finance.yahoo.com";
    private static final String USD_KRW_SYMBOL = "KRW=X";
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.UTC;

    private final WebClient yahooWebClient;

    public YahooUsdKrwRateAdapter() {
        this.yahooWebClient = WebClient.builder()
                .baseUrl(YAHOO_FINANCE_BASE_URL)
                .build();
    }

    @Override
    public Map<LocalDate, BigDecimal> readUsdKrwRates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            return Map.of();
        }

        try {
            YahooQuoteResponse response = yahooWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v8/finance/chart/{symbol}")
                            .queryParam("period1", toEpochSecond(startDate))
                            .queryParam("period2", toEpochSecond(endDate.plusDays(1)))
                            .queryParam("interval", "1d")
                            .build(USD_KRW_SYMBOL))
                    .retrieve()
                    .bodyToMono(YahooQuoteResponse.class)
                    .block();

            return extractHistoricalRates(response);
        } catch (Exception e) {
            log.warn("Yahoo USD/KRW history read failed: startDate={}, endDate={}, reason={}",
                    startDate, endDate, e.toString());
            return Map.of();
        }
    }

    @Override
    public Optional<BigDecimal> getCurrentUsdKrwRate() {
        try {
            YahooQuoteResponse response = yahooWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v8/finance/chart/{symbol}")
                            .build(USD_KRW_SYMBOL))
                    .retrieve()
                    .bodyToMono(YahooQuoteResponse.class)
                    .block();

            if (response == null) {
                return Optional.empty();
            }

            BigDecimal price = response.getCurrentPrice();
            if (price == null || price.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(price);
        } catch (Exception e) {
            log.warn("Yahoo USD/KRW current read failed: reason={}", e.toString());
            return Optional.empty();
        }
    }

    private Map<LocalDate, BigDecimal> extractHistoricalRates(YahooQuoteResponse response) {
        if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
            return Map.of();
        }

        YahooQuoteResponse.Result result = response.chart().result().getFirst();
        List<Long> timestamps = result.timestamp();
        if (timestamps == null || timestamps.isEmpty()) {
            return Map.of();
        }
        if (result.indicators() == null || result.indicators().quote() == null || result.indicators().quote().isEmpty()) {
            return Map.of();
        }

        List<BigDecimal> closes = result.indicators().quote().getFirst().close();
        if (closes == null || closes.isEmpty()) {
            return Map.of();
        }

        ZoneId zoneId = resolveZoneId(result.meta());
        int size = Math.min(timestamps.size(), closes.size());
        Map<LocalDate, BigDecimal> rates = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            Long timestamp = timestamps.get(i);
            BigDecimal close = closes.get(i);
            if (timestamp == null || close == null || close.signum() <= 0) {
                continue;
            }
            LocalDate date = Instant.ofEpochSecond(timestamp)
                    .atZone(zoneId)
                    .toLocalDate();
            rates.put(date, close);
        }
        return rates;
    }

    private long toEpochSecond(LocalDate date) {
        return date.atStartOfDay(DEFAULT_ZONE).toEpochSecond();
    }

    private ZoneId resolveZoneId(YahooQuoteResponse.Meta meta) {
        if (meta == null || meta.exchangeTimezoneName() == null || meta.exchangeTimezoneName().isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(meta.exchangeTimezoneName());
        } catch (Exception e) {
            log.debug("Yahoo USD/KRW timezone fallback applied: timezone={}, reason={}",
                    meta.exchangeTimezoneName(), e.toString());
            return DEFAULT_ZONE;
        }
    }
}
