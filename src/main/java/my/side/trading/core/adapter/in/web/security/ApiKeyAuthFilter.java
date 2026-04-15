package my.side.trading.core.adapter.in.web.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ApiKeyAuthFilter implements Filter {

    private final String validApiKey;
    private final List<String> publicPathPrefixes;
    private static final String API_KEY_HEADER = "X-API-KEY";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        // 1. 대시보드 및 Swagger는 인증 제외
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. 그 외 API는 API Key 검증
        String clientApiKey = httpRequest.getHeader(API_KEY_HEADER);
        if (!validApiKey.equals(clientApiKey)) {
            log.warn("유효하지 않은 API Key 접근 시도입니다. IP={}, Path={}", request.getRemoteAddr(), path);
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API Key");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        return publicPathPrefixes.stream().anyMatch(path::startsWith);
    }
}
