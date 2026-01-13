package my.side.trading.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "trading.strategy")
public record TradingStrategyProps(
        BigDecimal tolerancePct, // 비중 오차 허용치 (%)
        List<String> symbols, // 운용 종목 목록
        List<String> sellPriority, // 매도 우선순위 (레버리지 높은 순)
        List<String> buyPriority // 매수 우선순위 (레버리지 낮은 순)
) {
    // 설정이 없을 경우 기본값 제공
    public BigDecimal tolerancePct() {
        return tolerancePct != null ? tolerancePct : new BigDecimal("5.0");
    }

    public List<String> symbols() {
        return symbols != null ? symbols : List.of("QQQ", "QLD", "TQQQ");
    }

    public List<String> sellPriority() {
        return sellPriority != null ? sellPriority : List.of("TQQQ", "QLD", "QQQ");
    }

    public List<String> buyPriority() {
        return buyPriority != null ? buyPriority : List.of("QQQ", "QLD", "TQQQ");
    }
}
