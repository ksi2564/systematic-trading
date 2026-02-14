package my.side.trading.core.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private final String VALID_KEY = "test-key";
    private ApiKeyAuthFilter filter = new ApiKeyAuthFilter(VALID_KEY);

    @Test
    void 유효한_키_제공시_통과() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/jobs/execute");
        when(request.getHeader("X-API-KEY")).thenReturn(VALID_KEY);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void 유효하지_않은_키_제공시_401_응답() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/jobs/execute");
        when(request.getHeader("X-API-KEY")).thenReturn("wrong-key");

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API Key");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void 대시보드_경로는_키_없이_통과() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/dashboard/summary");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request, never()).getHeader(anyString()); // 헤더 체크조차 안함
    }

    // === 보안 테스트 확장: /kis/*, /execution/* 경로 인증 검증 ===

    @Test
    void KIS_주문_경로는_키_없으면_401_응답() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/kis/overseas/order/us/buy");
        when(request.getHeader("X-API-KEY")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API Key");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void KIS_토큰_경로는_키_없으면_401_응답() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/kis/token");
        when(request.getHeader("X-API-KEY")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API Key");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void 리밸런싱_실행_경로는_키_없으면_401_응답() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/execution/rebalance/run");
        when(request.getHeader("X-API-KEY")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API Key");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void KIS_경로에_유효한_키_제공시_통과() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/kis/overseas/order/us/buy");
        when(request.getHeader("X-API-KEY")).thenReturn(VALID_KEY);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void Actuator_헬스체크_경로는_키_없이_통과() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/actuator/health");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request, never()).getHeader(anyString());
    }

    @Test
    void Swagger_경로는_키_없이_통과() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request, never()).getHeader(anyString());
    }
}
