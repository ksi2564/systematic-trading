package my.side.trading.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "trading.strategy")
public record TradingStrategyProps(
        BigDecimal tolerancePct, // 비중 오차 허용치 (%)
        String signalSymbol, // 전략 판단 기준 ETF 심볼
        List<String> symbols, // 운용 종목 목록
        List<String> sellPriority, // 매도 우선순위 (레버리지 높은 순)
        List<String> buyPriority // 매수 우선순위 (레버리지 낮은 순)
) {
    @ConstructorBinding
    public TradingStrategyProps {
    }

    public TradingStrategyProps(
            BigDecimal tolerancePct,
            List<String> symbols,
            List<String> sellPriority,
            List<String> buyPriority
    ) {
        this(tolerancePct, null, symbols, sellPriority, buyPriority);
    }

    // 설정이 없을 경우 기본값 제공
    public BigDecimal tolerancePct() {
        return tolerancePct != null ? tolerancePct : new BigDecimal("5.0");
    }

    public String signalSymbol() {
        return normalizeSymbol(signalSymbol, "QQQM");
    }

    public List<String> symbols() {
        return symbols != null ? symbols.stream().map(this::normalizeSymbol).toList() : List.of(signalSymbol(), "QLD", "TQQQ");
    }

    public List<String> sellPriority() {
        return sellPriority != null ? sellPriority.stream().map(this::normalizeSymbol).toList() : List.of("TQQQ", "QLD", signalSymbol());
    }

    public List<String> buyPriority() {
        return buyPriority != null ? buyPriority.stream().map(this::normalizeSymbol).toList() : List.of(signalSymbol(), "QLD", "TQQQ");
    }

    private String normalizeSymbol(String value) {
        return normalizeSymbol(value, null);
    }

    private String normalizeSymbol(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toUpperCase();
    }
}
