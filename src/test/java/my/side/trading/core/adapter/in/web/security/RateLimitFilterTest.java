package my.side.trading.core.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
    }

    @Test
    void 제한_내_요청은_통과() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/jobs/execute");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        // 10회까지는 통과
        for (int i = 0; i < 10; i++) {
            filter.doFilter(request, response, chain);
        }

        verify(chain, times(10)).doFilter(request, response);
    }

    @Test
    void 제한_초과_요청은_429_응답() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/jobs/execute");
        when(request.getRemoteAddr()).thenReturn("192.168.0.1");

        // 11번 요청
        for (int i = 0; i < 11; i++) {
            filter.doFilter(request, response, chain);
        }

        // 10번은 통과, 1번은 차단
        verify(chain, times(10)).doFilter(request, response);
        verify(response, times(1)).sendError(429, "Too Many Requests");
    }

    @Test
    void 공개_경로는_레이트리밋_적용_제외() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/actuator/health");

        // 20회 요청해도 모두 통과 (레이트리밋 미적용)
        for (int i = 0; i < 20; i++) {
            filter.doFilter(request, response, chain);
        }

        verify(chain, times(20)).doFilter(request, response);
        verify(request, never()).getRemoteAddr(); // IP 체크조차 안함
    }
}
