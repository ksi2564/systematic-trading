package my.side.trading.core.adapter.in.web.security;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.infrastructure.config.TradingSecurityProps;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class WebFilterConfig {

    private final TradingSecurityProps securityProps;

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter() {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(securityProps.publicPathPrefixes()));
        registration.addUrlPatterns("/*"); // 전체 경로에 적용 (공개 경로는 필터 내부에서 제외)
        registration.setOrder(1); // 가장 먼저 실행
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter() {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyAuthFilter(securityProps.apiKey(), securityProps.publicPathPrefixes()));
        registration.addUrlPatterns("/*"); // 전체 경로에 적용 (공개 경로는 필터 내부에서 제외)
        registration.setOrder(2); // Rate Limit 통과 후 인증 체크
        return registration;
    }
}
