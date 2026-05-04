package my.side.trading.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "trading.fx")
public record TradingFxProps(
        YahooProps yahoo
) {
    private static final Duration DEFAULT_CURRENT_CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_STALE_SUCCESS_TTL = Duration.ofMinutes(30);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_HISTORY_CACHE_TTL = Duration.ofDays(365);
    private static final long DEFAULT_HISTORY_CACHE_MAXIMUM_SIZE = 1_200L;

    public TradingFxProps {
        yahoo = yahoo == null
                ? new YahooProps(
                DEFAULT_CURRENT_CACHE_TTL,
                DEFAULT_STALE_SUCCESS_TTL,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_HISTORY_CACHE_TTL,
                DEFAULT_HISTORY_CACHE_MAXIMUM_SIZE)
                : yahoo;
    }

    public record YahooProps(
            Duration currentCacheTtl,
            Duration staleSuccessTtl,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration historyCacheTtl,
            long historyCacheMaximumSize
    ) {
        public YahooProps {
            currentCacheTtl = normalizeDuration(currentCacheTtl, DEFAULT_CURRENT_CACHE_TTL);
            staleSuccessTtl = normalizeDuration(staleSuccessTtl, DEFAULT_STALE_SUCCESS_TTL);
            if (staleSuccessTtl.compareTo(currentCacheTtl) < 0) {
                staleSuccessTtl = currentCacheTtl;
            }
            connectTimeout = normalizeDuration(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
            requestTimeout = normalizeDuration(requestTimeout, DEFAULT_REQUEST_TIMEOUT);
            historyCacheTtl = normalizeDuration(historyCacheTtl, DEFAULT_HISTORY_CACHE_TTL);
            historyCacheMaximumSize = historyCacheMaximumSize <= 0
                    ? DEFAULT_HISTORY_CACHE_MAXIMUM_SIZE
                    : historyCacheMaximumSize;
        }

        private static Duration normalizeDuration(Duration value, Duration defaultValue) {
            if (value == null || value.isZero() || value.isNegative()) {
                return defaultValue;
            }
            return value;
        }
    }
}
