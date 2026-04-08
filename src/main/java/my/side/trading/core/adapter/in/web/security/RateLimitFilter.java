package my.side.trading.core.adapter.in.web.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RateLimitFilter implements Filter {

    private final Cache<String, Bucket> buckets;
    private final PublicRequestClientResolver clientResolver;
    private final int publicRateLimitPerMinute;

    public RateLimitFilter(List<String> publicPathPrefixes, int publicRateLimitPerMinute) {
        this(new PublicRequestClientResolver(publicPathPrefixes, "X-Forwarded-For", List.of()),
                publicRateLimitPerMinute);
    }

    public RateLimitFilter(PublicRequestClientResolver clientResolver, int publicRateLimitPerMinute) {
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build();
        this.clientResolver = clientResolver;
        this.publicRateLimitPerMinute = publicRateLimitPerMinute;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();
        boolean publicPath = clientResolver.isPublicPath(path);
        String clientIp = publicPath
                ? clientResolver.resolveClientIp(httpRequest)
                : normalizeValue(httpRequest.getRemoteAddr());
        Bucket bucket = buckets.get(rateLimitKey(clientIp, publicPath), key -> createNewBucket(publicPath));

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn(
                    "Rate limit exceeded for IP: {}, RemoteAddr={}, Path={}, Public={}, ForwardedHeader={}",
                    clientIp,
                    normalizeValue(httpRequest.getRemoteAddr()),
                    path,
                    publicPath,
                    normalizeValue(httpRequest.getHeader(clientResolver.forwardedClientIpHeader()))
            );
            ((HttpServletResponse) response).sendError(429, "Too Many Requests");
        }
    }

    private String rateLimitKey(String clientIp, boolean publicPath) {
        return (publicPath ? "public:" : "private:") + clientIp;
    }

    private Bucket createNewBucket(boolean publicPath) {
        return Bucket.builder().addLimit(publicPath ? publicBandwidth() : privateBandwidth()).build();
    }

    private Bandwidth privateBandwidth() {
        return Bandwidth.builder()
                .capacity(10)
                .refillGreedy(10, Duration.ofSeconds(1))
                .build();
    }

    private Bandwidth publicBandwidth() {
        return Bandwidth.builder()
                .capacity(publicRateLimitPerMinute)
                .refillGreedy(publicRateLimitPerMinute, Duration.ofMinutes(1))
                .build();
    }

    private String normalizeValue(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
