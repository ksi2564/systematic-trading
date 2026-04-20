package my.side.trading.core.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicRequestClientResolverTest {

    @Test
    void 신뢰된_프록시에서온_공개경로는_forwarded_ip를_사용한다() {
        PublicRequestClientResolver resolver = new PublicRequestClientResolver(
                List.of("/public/api"),
                "X-Forwarded-For",
                List.of("10.0.0.0/8"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/public/api/v1/summary");
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 10.1.2.3");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void 로컬_caddy_뒤의_cloudflare_공개경로는_cf_connecting_ip를_사용한다() {
        PublicRequestClientResolver resolver = new PublicRequestClientResolver(
                List.of("/public/api"),
                "CF-Connecting-IP",
                List.of("127.0.0.1/32"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/public/api/v1/summary");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("CF-Connecting-IP")).thenReturn("198.51.100.7");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void 신뢰되지않은_프록시에서는_forwarded_ip를_무시한다() {
        PublicRequestClientResolver resolver = new PublicRequestClientResolver(
                List.of("/public/api"),
                "X-Forwarded-For",
                List.of("10.0.0.0/8"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/public/api/v1/summary");
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void 비공개경로는_forwarded_ip를_사용하지않는다() {
        PublicRequestClientResolver resolver = new PublicRequestClientResolver(
                List.of("/public/api"),
                "X-Forwarded-For",
                List.of("10.0.0.0/8"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/jobs/manual-rebalance");
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("10.1.2.3");
    }

    @Test
    void 신뢰프록시가없으면_remoteAddr를_사용한다() {
        PublicRequestClientResolver resolver = new PublicRequestClientResolver(
                List.of("/public/api"),
                "X-Forwarded-For",
                List.of());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/public/api/v1/summary");
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("10.1.2.3");
    }
}
