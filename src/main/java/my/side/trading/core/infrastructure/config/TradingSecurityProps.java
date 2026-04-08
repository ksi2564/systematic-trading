package my.side.trading.core.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "trading.security")
public record TradingSecurityProps(
        @NotBlank String apiKey,
        List<String> publicPathPrefixes,
        PublicPathProtectionMode publicPathProtectionMode,
        String publicPathProtectionNote,
        List<String> publicReadAllowedOrigins,
        int publicRateLimitPerMinute,
        int publicCacheMaxAgeSeconds
) {
    private static final int DEFAULT_PUBLIC_RATE_LIMIT_PER_MINUTE = 60;
    private static final int DEFAULT_PUBLIC_CACHE_MAX_AGE_SECONDS = 300;

    public TradingSecurityProps {
        publicPathPrefixes = publicPathPrefixes == null
                ? List.of()
                : publicPathPrefixes.stream()
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .toList();
        publicPathProtectionMode = publicPathProtectionMode == null
                ? PublicPathProtectionMode.UNSPECIFIED
                : publicPathProtectionMode;
        publicPathProtectionNote = publicPathProtectionNote == null
                ? ""
                : publicPathProtectionNote.trim();
        publicReadAllowedOrigins = publicReadAllowedOrigins == null
                ? List.of()
                : publicReadAllowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        publicRateLimitPerMinute = publicRateLimitPerMinute <= 0
                ? DEFAULT_PUBLIC_RATE_LIMIT_PER_MINUTE
                : publicRateLimitPerMinute;
        publicCacheMaxAgeSeconds = publicCacheMaxAgeSeconds <= 0
                ? DEFAULT_PUBLIC_CACHE_MAX_AGE_SECONDS
                : publicCacheMaxAgeSeconds;
    }
}
