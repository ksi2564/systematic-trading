package my.side.trading.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "trading.security")
public record TradingSecurityProps(
        String apiKey,
        List<String> publicPathPrefixes
) {
    public TradingSecurityProps {
        publicPathPrefixes = publicPathPrefixes == null ? List.of() : List.copyOf(publicPathPrefixes);
    }
}
