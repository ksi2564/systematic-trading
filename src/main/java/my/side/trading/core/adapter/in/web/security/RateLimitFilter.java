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
    private final List<String> publicPathPrefixes;

    public RateLimitFilter(List<String> publicPathPrefixes) {
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build();
        this.publicPathPrefixes = publicPathPrefixes == null ? List.of() : List.copyOf(publicPathPrefixes);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // 공개 경로는 레이트리밋 적용 제외
        if (isPublicPath(httpRequest.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = httpRequest.getRemoteAddr();

        Bucket bucket = buckets.get(clientIp, key -> createNewBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            ((HttpServletResponse) response).sendError(429, "Too Many Requests");
        }
    }

    /**
     * 레이트리밋 적용 제외 대상인 공개 경로 여부 확인
     */
    private boolean isPublicPath(String path) {
        return publicPathPrefixes.stream().anyMatch(path::startsWith);
    }

    private Bucket createNewBucket() {
        // 초당 10개 허용 (Capacity 10, Refill 10 tokens per second)
        Bandwidth limit = Bandwidth.builder()
                .capacity(10)
                .refillGreedy(10, Duration.ofSeconds(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
