package my.side.trading.core.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "trading.security")
public record TradingSecurityProps(
        @NotBlank String apiKey,
        List<String> publicPathPrefixes
) {
    public TradingSecurityProps {
        publicPathPrefixes = publicPathPrefixes == null
                ? List.of()
                : publicPathPrefixes.stream()
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .toList();
    }
}
