package my.side.trading.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "trading.circuit-breaker")
public record TradingCircuitBreakerProps(
        Boolean enabled, // 전체 on/off
        Boolean vixEnabled, // VIX 필터만 on/off
        BigDecimal vixThreshold, // VIX 임계값 (기본값: 35)
        Integer maPeriod // 이동평균 기간 (기본값: 200)
) {
    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    public boolean isVixEnabled() {
        return vixEnabled != null && vixEnabled;
    }

    public BigDecimal getVixThreshold() {
        return vixThreshold != null ? vixThreshold : new BigDecimal("35");
    }

    public int getMaPeriod() {
        return maPeriod != null ? maPeriod : 200;
    }
}
