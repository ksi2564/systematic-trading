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
import java.util.List;

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
        filter = new RateLimitFilter(List.of(), 3);
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
    void 공개_경로는_공개용_별도_레이트리밋을_적용한다() throws ServletException, IOException {
        RateLimitFilter publicFilter = new RateLimitFilter(List.of("/public/api"), 3);
        when(request.getRequestURI()).thenReturn("/public/api/v1/summary");
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");

        for (int i = 0; i < 4; i++) {
            publicFilter.doFilter(request, response, chain);
        }

        verify(chain, times(3)).doFilter(request, response);
        verify(response).sendError(429, "Too Many Requests");
    }

    @Test
    void 대시보드_경로도_기본값에서는_레이트리밋_적용_대상() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/dashboard/summary");
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");

        for (int i = 0; i < 11; i++) {
            filter.doFilter(request, response, chain);
        }

        verify(chain, times(10)).doFilter(request, response);
        verify(response).sendError(429, "Too Many Requests");
    }
}
