package my.side.trading.core.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

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
