package my.side.trading.core.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SecurityPublicPathPolicyValidator {

    private static final List<String> PROD_PROTECTED_PREFIXES = List.of(
            "/api/dashboard",
            "/api/jobs",
            "/execution",
            "/kis",
            "/actuator"
    );
    private static final String SAMPLE_PUBLIC_ORIGIN_HOST = "example.com";
    private static final String SAMPLE_TRUSTED_PROXY_RANGE = "10.0.0.0/8";

    private final TradingSecurityProps securityProps;
    private final Environment environment;

    @PostConstruct
    void validate() {
        validatePublicPathProtectionDeclaration();

        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        List<String> violations = securityProps.publicPathPrefixes().stream()
                .filter(SecurityPublicPathPolicyValidator::isProdProtectedPrefix)
                .toList();

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "prod profile에서는 운영 API/Actuator 경로를 public-path-prefixes로 열 수 없습니다: " + violations
            );
        }

        if (securityProps.publicPathPrefixes().isEmpty()) {
            return;
        }

        validateProdPublicReadOrigins();
        validateProdReverseProxyRanges();
    }

    private void validatePublicPathProtectionDeclaration() {
        if (securityProps.publicPathPrefixes().isEmpty()) {
            return;
        }

        if (securityProps.publicPathProtectionMode() == PublicPathProtectionMode.UNSPECIFIED) {
            throw new IllegalStateException(
                    "공개 경로를 열 때는 trading.security.public-path-protection-mode를 명시해야 합니다."
            );
        }

        if (securityProps.publicPathProtectionNote().isBlank()) {
            throw new IllegalStateException(
                    "공개 경로를 열 때는 trading.security.public-path-protection-note를 비워둘 수 없습니다."
            );
        }

        if (securityProps.publicPathProtectionMode() == PublicPathProtectionMode.REVERSE_PROXY
                && securityProps.publicTrustedProxyRanges().isEmpty()) {
            throw new IllegalStateException(
                    "REVERSE_PROXY 모드에서는 trading.security.public-trusted-proxy-ranges를 비워둘 수 없습니다."
            );
        }
    }

    private void validateProdPublicReadOrigins() {
        if (securityProps.publicReadAllowedOrigins().isEmpty()) {
            throw new IllegalStateException(
                    "prod profile에서 공개 읽기 API를 열 때는 trading.security.public-read-allowed-origins를 실제 HTTPS origin으로 설정해야 합니다."
            );
        }

        securityProps.publicReadAllowedOrigins().forEach(this::validateProdPublicReadOrigin);
    }

    private void validateProdPublicReadOrigin(String origin) {
        if (origin.contains("*")) {
            throw new IllegalStateException(
                    "prod profile에서는 public-read-allowed-origins에 wildcard를 사용할 수 없습니다: " + origin
            );
        }

        URI parsedOrigin;
        try {
            parsedOrigin = URI.create(origin);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "prod profile에서는 public-read-allowed-origins를 유효한 origin으로 설정해야 합니다: " + origin,
                    ex
            );
        }

        if (!"https".equalsIgnoreCase(parsedOrigin.getScheme())) {
            throw new IllegalStateException(
                    "prod profile에서는 public-read-allowed-origins에 HTTPS origin만 허용합니다: " + origin
            );
        }

        if (parsedOrigin.getHost() == null || parsedOrigin.getHost().isBlank()) {
            throw new IllegalStateException(
                    "prod profile에서는 public-read-allowed-origins에 host가 있는 origin만 허용합니다: " + origin
            );
        }

        if (parsedOrigin.getPath() != null && !parsedOrigin.getPath().isBlank()) {
            throw new IllegalStateException(
                    "prod profile에서는 public-read-allowed-origins에 path를 포함할 수 없습니다: " + origin
            );
        }

        if (parsedOrigin.getQuery() != null || parsedOrigin.getFragment() != null || parsedOrigin.getUserInfo() != null) {
            throw new IllegalStateException(
                    "prod profile에서는 public-read-allowed-origins에 origin 외 추가 정보를 포함할 수 없습니다: " + origin
            );
        }

        String host = parsedOrigin.getHost().toLowerCase();
        if (host.equals(SAMPLE_PUBLIC_ORIGIN_HOST) || host.endsWith("." + SAMPLE_PUBLIC_ORIGIN_HOST)) {
            throw new IllegalStateException(
                    "prod profile에서는 example.com 계열 샘플 origin을 사용할 수 없습니다: " + origin
            );
        }
    }

    private void validateProdReverseProxyRanges() {
        if (securityProps.publicPathProtectionMode() != PublicPathProtectionMode.REVERSE_PROXY) {
            return;
        }

        if (securityProps.publicTrustedProxyRanges().stream().anyMatch(SAMPLE_TRUSTED_PROXY_RANGE::equals)) {
            throw new IllegalStateException(
                    "prod profile에서는 샘플 CIDR(" + SAMPLE_TRUSTED_PROXY_RANGE + ")을 public-trusted-proxy-ranges에 둘 수 없습니다."
            );
        }
    }

    static boolean isProdProtectedPrefix(String configuredPrefix) {
        return PROD_PROTECTED_PREFIXES.stream()
                .anyMatch(protectedPrefix -> overlaps(configuredPrefix, protectedPrefix));
    }

    private static boolean overlaps(String left, String right) {
        return startsWithPath(left, right) || startsWithPath(right, left);
    }

    private static boolean startsWithPath(String path, String prefix) {
        if (path.equals(prefix)) {
            return true;
        }
        if ("/".equals(prefix)) {
            return path.startsWith("/");
        }
        String normalizedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        return path.startsWith(normalizedPrefix);
    }
}
