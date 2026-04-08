package my.side.trading.core.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class PublicRequestClientResolver {

    private static final String UNKNOWN_IP = "unknown";

    private final List<String> publicPathPrefixes;
    private final String forwardedClientIpHeader;
    private final List<IpAddressRange> trustedProxyRanges;

    public PublicRequestClientResolver(
            List<String> publicPathPrefixes,
            String forwardedClientIpHeader,
            List<String> trustedProxyRanges
    ) {
        this.publicPathPrefixes = publicPathPrefixes == null ? List.of() : List.copyOf(publicPathPrefixes);
        this.forwardedClientIpHeader = forwardedClientIpHeader == null || forwardedClientIpHeader.isBlank()
                ? "X-Forwarded-For"
                : forwardedClientIpHeader.trim();
        this.trustedProxyRanges = trustedProxyRanges == null
                ? List.of()
                : trustedProxyRanges.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(range -> !range.isEmpty())
                .map(IpAddressRange::parse)
                .toList();
    }

    public boolean isPublicPath(String path) {
        return publicPathPrefixes.stream().anyMatch(path::startsWith);
    }

    public String forwardedClientIpHeader() {
        return forwardedClientIpHeader;
    }

    public String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = normalizeIp(request.getRemoteAddr());
        if (!isPublicPath(request.getRequestURI()) || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        return extractForwardedClientIp(request.getHeader(forwardedClientIpHeader))
                .orElse(remoteAddr);
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (trustedProxyRanges.isEmpty()) {
            return false;
        }
        return trustedProxyRanges.stream().anyMatch(range -> range.matches(remoteAddr));
    }

    private Optional<String> extractForwardedClientIp(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return Optional.empty();
        }

        return Arrays.stream(headerValue.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .filter(token -> !"unknown".equalsIgnoreCase(token))
                .findFirst();
    }

    private String normalizeIp(String value) {
        return value == null || value.isBlank() ? UNKNOWN_IP : value.trim();
    }
}
