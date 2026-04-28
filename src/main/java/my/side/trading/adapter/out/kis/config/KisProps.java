package my.side.trading.adapter.out.kis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "kis")
public record KisProps(
        String baseUrl,
        String appKey,
        String appSecret,
        String accountNo,
        String cano,
        String acntPrdtCd,
        Duration connectTimeout,
        Duration requestTimeout
) {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    public KisProps {
        connectTimeout = normalizeDuration(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        requestTimeout = normalizeDuration(requestTimeout, DEFAULT_REQUEST_TIMEOUT);
    }

    private static Duration normalizeDuration(Duration value, Duration defaultValue) {
        if (value == null || value.isZero() || value.isNegative()) {
            return defaultValue;
        }
        return value;
    }
}
