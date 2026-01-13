package my.side.trading.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.execution")
public record TradingExecutionProps(
        boolean enabled
) {}
