package my.side.trading.adapter.out.kis.client;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.*;
import my.side.trading.shared.security.SensitiveDataSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
@Slf4j
@RequiredArgsConstructor
public class KisAuthService {

    private static final String TOKEN_CACHE_KEY = "KIS_ACCESS_TOKEN";

    private final WebClient kisWebClient;
    private final KisProps props;
    private final Cache<String, KisToken> kisTokenCache;

    public String getAccessToken() {
        KisToken cached = kisTokenCache.getIfPresent(TOKEN_CACHE_KEY);
        if (cached != null && cached.isValid()) {
            return cached.accessToken();
        }

        // 동시 갱신 방지를 위한 synchronized
        synchronized (this) {
            KisToken doubleCheck = kisTokenCache.getIfPresent(TOKEN_CACHE_KEY);
            if (doubleCheck != null && doubleCheck.isValid()) {
                return doubleCheck.accessToken();
            }

            KisTokenResponse response = kisWebClient.post()
                    .uri("/oauth2/tokenP")
                    .bodyValue(KisTokenRequest.of(props.appKey(), props.appSecret()))
                    .retrieve()
                    .bodyToMono(KisTokenResponse.class)
                    .block(props.requestTimeout());

            if (response == null || response.accessToken() == null) {
                throw new IllegalStateException("KIS 접근토큰 발급 실패: response null");
            }

            long expiresInMs = response.expiresIn() * 1000L;
            // 5분 일찍 만료 처리(안전 버퍼)
            long safetyMs = 300_000L;
            long expiresAt = System.currentTimeMillis() + Math.max(0, expiresInMs - safetyMs);

            KisToken newToken = new KisToken(response.accessToken(), expiresAt);
            kisTokenCache.put(TOKEN_CACHE_KEY, newToken);

            return newToken.accessToken();
        }
    }

    public KisTokenDiagnosticsSnapshot inspectAccessTokenCache() {
        KisToken cached = kisTokenCache.getIfPresent(TOKEN_CACHE_KEY);
        return toSnapshot(cached);
    }

    public KisTokenDiagnosticsResult ensureAccessTokenForDiagnostics() {
        KisToken before = kisTokenCache.getIfPresent(TOKEN_CACHE_KEY);
        boolean cacheHit = before != null && before.isValid();
        getAccessToken();
        return new KisTokenDiagnosticsResult(
                true,
                cacheHit ? "CACHE" : "REMOTE",
                inspectAccessTokenCache()
        );
    }

    /**
     * 실시간(WebSocket)용 approval_key 발급
     */
    public String issueApprovalKey() {
        return kisWebClient.post()
                .uri("/oauth2/Approval")
                .bodyValue(KisApprovalKeyRequest.of(
                        props.appKey(),
                        props.appSecret()
                ))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(KisApprovalKeyResponse.class)
                                .map(KisApprovalKeyResponse::approvalKey);
                    } else {
                        return response.bodyToMono(String.class)
                                .flatMap(body -> {
                                    String sanitizedBody = SensitiveDataSanitizer.sanitize(body);
                                    String msg = "[KIS APPROVAL ERROR] status="
                                            + response.statusCode().value()
                                            + ", body=" + sanitizedBody;
                                    log.warn("KIS approval key 발급이 실패했습니다: status={}, body={}",
                                            response.statusCode().value(), sanitizedBody);
                                    return Mono.error(new IllegalStateException(msg));
                                });
                    }
                })
                .block(props.requestTimeout());
    }

    private KisTokenDiagnosticsSnapshot toSnapshot(KisToken token) {
        if (token == null) {
            return KisTokenDiagnosticsSnapshot.empty();
        }

        long now = System.currentTimeMillis();
        long ttlMillis = Math.max(0, token.expiresAtMills() - now);
        String accessToken = token.accessToken();
        return new KisTokenDiagnosticsSnapshot(
                true,
                token.isValid(),
                Instant.ofEpochMilli(token.expiresAtMills()),
                ttlMillis / 1000,
                accessToken == null ? 0 : accessToken.length(),
                fingerprint(accessToken)
        );
    }

    private String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest를 사용할 수 없습니다.", e);
        }
    }
}
