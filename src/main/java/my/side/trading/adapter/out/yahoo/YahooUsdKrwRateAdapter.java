package my.side.trading.adapter.out.yahoo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.yahoo.dto.YahooQuoteResponse;
import my.side.trading.core.application.port.out.CurrentFxRateProvider;
import my.side.trading.core.application.port.out.FxRateReader;
import my.side.trading.core.infrastructure.config.TradingFxProps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Primary
@Component
public class YahooUsdKrwRateAdapter implements FxRateReader, CurrentFxRateProvider {

    private static final String USD_KRW_SYMBOL = "KRW=X";
    private static final ZoneId DEFAULT_ZONE = ZoneOffset.UTC;
    private static final String CURRENT_RATE_CACHE_KEY = "USD/KRW";

    private final WebClient yahooWebClient;
    private final TradingFxProps props;
    private final Clock clock;
    private final Cache<String, CachedFxRate> currentRateCache;
    private final Cache<LocalDate, BigDecimal> historicalRateCache;
    private final AtomicReference<CachedFxRate> lastSuccessfulCurrentRate;

    @Autowired
    public YahooUsdKrwRateAdapter(@Qualifier("yahooWebClient") WebClient yahooWebClient, TradingFxProps props) {
        this(yahooWebClient, props, Clock.systemUTC());
    }

    YahooUsdKrwRateAdapter(WebClient yahooWebClient, TradingFxProps props, Clock clock) {
        this.yahooWebClient = yahooWebClient;
        this.props = props;
        this.clock = clock;
        Ticker ticker = () -> Instant.now(clock).toEpochMilli() * 1_000_000L;
        this.currentRateCache = Caffeine.newBuilder()
                .maximumSize(1)
                .ticker(ticker)
                .expireAfterWrite(props.yahoo().currentCacheTtl())
                .build();
        this.historicalRateCache = Caffeine.newBuilder()
                .maximumSize(props.yahoo().historyCacheMaximumSize())
                .ticker(ticker)
                .expireAfterWrite(props.yahoo().historyCacheTtl())
                .build();
        this.lastSuccessfulCurrentRate = new AtomicReference<>();
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
                    .block(props.yahoo().requestTimeout());

            Map<LocalDate, BigDecimal> fetched = extractHistoricalRates(response);
            if (!fetched.isEmpty()) {
                fetched.forEach(historicalRateCache::put);
                Map<LocalDate, BigDecimal> resolved = cachedHistoricalRatesBetween(startDate, endDate);
                if (resolved.size() > fetched.size()) {
                    log.info("Yahoo USD/KRW 이력 캐시가 누락 일자를 보완했습니다: startDate={}, endDate={}, fetched={}, resolved={}",
                            startDate, endDate, fetched.size(), resolved.size());
                }
                return resolved;
            }
            return fallbackHistoricalRates(startDate, endDate, "응답 비어 있음");
        } catch (Exception e) {
            log.warn("Yahoo USD/KRW 이력 조회에 실패했습니다: startDate={}, endDate={}, reason={}",
                    startDate, endDate, e.toString());
            return fallbackHistoricalRates(startDate, endDate, e.toString());
        }
    }

    @Override
    public Optional<BigDecimal> getCurrentUsdKrwRate() {
        CachedFxRate cached = currentRateCache.getIfPresent(CURRENT_RATE_CACHE_KEY);
        if (cached != null) {
            return Optional.of(cached.rate());
        }

        try {
            YahooQuoteResponse response = yahooWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v8/finance/chart/{symbol}")
                            .build(USD_KRW_SYMBOL))
                    .retrieve()
                    .bodyToMono(YahooQuoteResponse.class)
                    .block(props.yahoo().requestTimeout());

            if (response == null) {
                return fallbackCurrentRate("응답 null");
            }

            BigDecimal price = response.getCurrentPrice();
            if (price == null || price.signum() <= 0) {
                return fallbackCurrentRate("유효하지 않은 가격");
            }
            return Optional.of(cacheCurrentRate(price));
        } catch (Exception e) {
            log.warn("Yahoo USD/KRW 현재가 조회에 실패했습니다: reason={}", e.toString());
            return fallbackCurrentRate(e.toString());
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

    private BigDecimal cacheCurrentRate(BigDecimal price) {
        CachedFxRate cached = new CachedFxRate(price, Instant.now(clock));
        currentRateCache.put(CURRENT_RATE_CACHE_KEY, cached);
        lastSuccessfulCurrentRate.set(cached);
        return price;
    }

    private Optional<BigDecimal> fallbackCurrentRate(String reason) {
        CachedFxRate stale = lastSuccessfulCurrentRate.get();
        if (stale == null) {
            log.warn("Yahoo USD/KRW 현재가를 캐시 없이 복구할 수 없습니다: reason={}", reason);
            return Optional.empty();
        }

        Duration age = Duration.between(stale.fetchedAt(), Instant.now(clock));
        if (age.compareTo(props.yahoo().staleSuccessTtl()) > 0) {
            log.warn("Yahoo USD/KRW 현재가의 오래된 캐시 유효기간이 지났습니다: age={}, reason={}", age, reason);
            return Optional.empty();
        }

        log.warn("Yahoo USD/KRW 현재가에 오래된 캐시 대체값을 적용합니다: age={}, reason={}", age, reason);
        return Optional.of(stale.rate());
    }

    private Map<LocalDate, BigDecimal> fallbackHistoricalRates(LocalDate startDate, LocalDate endDate, String reason) {
        Map<LocalDate, BigDecimal> cached = cachedHistoricalRatesBetween(startDate, endDate);
        if (cached.isEmpty()) {
            log.warn("Yahoo USD/KRW 이력을 캐시 없이 복구할 수 없습니다: startDate={}, endDate={}, reason={}",
                    startDate, endDate, reason);
            return Map.of();
        }

        log.warn("Yahoo USD/KRW 이력에 캐시 대체값을 적용합니다: startDate={}, endDate={}, cachedCount={}, reason={}",
                startDate, endDate, cached.size(), reason);
        return cached;
    }

    private Map<LocalDate, BigDecimal> cachedHistoricalRatesBetween(LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, BigDecimal> cached = new LinkedHashMap<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            BigDecimal rate = historicalRateCache.getIfPresent(cursor);
            if (rate != null && rate.signum() > 0) {
                cached.put(cursor, rate);
            }
            cursor = cursor.plusDays(1);
        }
        return cached;
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
            log.debug("Yahoo USD/KRW timezone 기본값을 적용합니다: timezone={}, reason={}",
                    meta.exchangeTimezoneName(), e.toString());
            return DEFAULT_ZONE;
        }
    }

    private record CachedFxRate(
            BigDecimal rate,
            Instant fetchedAt
    ) {
    }
}
