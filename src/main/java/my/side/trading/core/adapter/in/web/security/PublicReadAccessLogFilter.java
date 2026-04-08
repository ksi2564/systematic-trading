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

@Slf4j(topic = "my.side.trading.publicapi.access")
@RequiredArgsConstructor
public class PublicReadAccessLogFilter implements Filter {

    private final PublicRequestClientResolver clientResolver;
    private final boolean accessLogEnabled;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!accessLogEnabled || !clientResolver.isPublicPath(httpRequest.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = clientResolver.resolveClientIp(httpRequest);
        String remoteAddr = safeValue(httpRequest.getRemoteAddr());
        String origin = safeValue(httpRequest.getHeader("Origin"));
        String forwardedFor = safeValue(httpRequest.getHeader(clientResolver.forwardedClientIpHeader()));
        long startedAt = System.nanoTime();
        Throwable failure = null;

        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            failure = ex;
            throw ex;
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info(
                    "public-read method={} path={} status={} clientIp={} remoteAddr={} origin={} forwardedFor={} failure={} durationMs={}",
                    httpRequest.getMethod(),
                    httpRequest.getRequestURI(),
                    httpResponse.getStatus(),
                    clientIp,
                    remoteAddr,
                    origin,
                    forwardedFor,
                    failure == null ? "-" : failure.getClass().getSimpleName(),
                    durationMs
            );
        }
    }

    private String safeValue(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
