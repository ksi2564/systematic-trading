package my.side.trading.core.adapter.in.web.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public class PublicReadCorsFilter implements Filter {

    private final List<String> publicPathPrefixes;
    private final List<String> allowedOrigins;

    public PublicReadCorsFilter(List<String> publicPathPrefixes, List<String> allowedOrigins) {
        this.publicPathPrefixes = publicPathPrefixes == null ? List.of() : List.copyOf(publicPathPrefixes);
        this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String origin = httpRequest.getHeader("Origin");

        if (isPublicPath(httpRequest.getRequestURI()) && isAllowedOrigin(origin)) {
            httpResponse.setHeader("Access-Control-Allow-Origin", origin);
            httpResponse.setHeader("Vary", "Origin");
            httpResponse.setHeader("Access-Control-Allow-Methods", "GET,OPTIONS");
            httpResponse.setHeader("Access-Control-Allow-Headers", "Content-Type,Accept");
        }

        chain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        return publicPathPrefixes.stream().anyMatch(path::startsWith);
    }

    private boolean isAllowedOrigin(String origin) {
        return origin != null && allowedOrigins.contains(origin);
    }
}
