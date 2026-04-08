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
import java.util.List;

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
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(VALID_KEY, List.of());

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
    void 대시보드_경로도_기본값에서는_인증이_필요하다() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/dashboard/summary");
        when(request.getHeader("X-API-KEY")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API Key");
        verify(chain, never()).doFilter(request, response);
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
    void 설정된_공개경로는_키_없이_통과() throws ServletException, IOException {
        ApiKeyAuthFilter publicFilter = new ApiKeyAuthFilter(VALID_KEY, List.of("/public/api", "/actuator"));
        when(request.getRequestURI()).thenReturn("/public/api/v1/summary");

        publicFilter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request, never()).getHeader(anyString());
    }

    @Test
    void Swagger_경로는_기본값에서는_인증이_필요하다() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");
        when(request.getHeader("X-API-KEY")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API Key");
        verify(chain, never()).doFilter(request, response);
    }
}
