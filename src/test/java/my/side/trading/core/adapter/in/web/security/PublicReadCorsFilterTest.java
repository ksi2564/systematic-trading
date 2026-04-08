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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicReadCorsFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    @Test
    void 허용된_origin은_공개경로에_cors헤더를_추가한다() throws ServletException, IOException {
        PublicReadCorsFilter filter = new PublicReadCorsFilter(
                List.of("/public/api"),
                List.of("https://portfolio.example.com"));
        when(request.getRequestURI()).thenReturn("/public/api/v1/summary");
        when(request.getHeader("Origin")).thenReturn("https://portfolio.example.com");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Access-Control-Allow-Origin", "https://portfolio.example.com");
        verify(response).setHeader("Vary", "Origin");
        verify(chain).doFilter(request, response);
    }

    @Test
    void 허용되지않은_origin은_cors헤더를_추가하지않는다() throws ServletException, IOException {
        PublicReadCorsFilter filter = new PublicReadCorsFilter(
                List.of("/public/api"),
                List.of("https://portfolio.example.com"));
        when(request.getRequestURI()).thenReturn("/public/api/v1/summary");
        when(request.getHeader("Origin")).thenReturn("https://unknown.example.com");

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader("Access-Control-Allow-Origin", "https://unknown.example.com");
        verify(chain).doFilter(request, response);
    }
}
