package my.side.trading.core.infrastructure.config;

import my.side.trading.core.domain.strategy.DrawdownThresholds;
import my.side.trading.core.domain.strategy.RecoveryThresholds;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "trading.strategy.thresholds")
public record TradingStrategyThresholdProps(
        DrawdownProps drawdown,
        RecoveryProps recovery
) {
    public TradingStrategyThresholdProps {
        drawdown = drawdown == null ? new DrawdownProps(null, null, null, null) : drawdown;
        recovery = recovery == null ? new RecoveryProps(null, null) : recovery;
    }

    public DrawdownThresholds drawdownThresholds() {
        return new DrawdownThresholds(
                drawdown.first(),
                drawdown.second(),
                drawdown.third(),
                drawdown.fourth());
    }

    public RecoveryThresholds recoveryThresholds() {
        return new RecoveryThresholds(
                recovery.activationMaxDrawdownPct(),
                recovery.recoveryDrawdownPct());
    }

    public record DrawdownProps(
            BigDecimal first,
            BigDecimal second,
            BigDecimal third,
            BigDecimal fourth
    ) {
    }

    public record RecoveryProps(
            BigDecimal activationMaxDrawdownPct,
            BigDecimal recoveryDrawdownPct
    ) {
    }
}
