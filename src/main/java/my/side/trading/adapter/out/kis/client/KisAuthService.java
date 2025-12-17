package my.side.trading.adapter.out.kis.client;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
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
                    .block();

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
                                    String msg = "[KIS APPROVAL ERROR] status="
                                            + response.statusCode().value()
                                            + ", body=" + body;
                                    System.err.println(msg);
                                    return Mono.error(new IllegalStateException(msg));
                                });
                    }
                })
                .block();
    }
}
