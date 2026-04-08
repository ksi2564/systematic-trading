package my.side.trading.core.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPublicPathPolicyValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(SecurityPolicyConfig.class)
            .withPropertyValues("trading.security.api-key=test-key");

    @Test
    void prod에서는_dashboard를_공개경로로_열수없다() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/api/dashboard",
                        "trading.security.public-path-protection-mode=VPN",
                        "trading.security.public-path-protection-note=운영 대시보드는 VPN 뒤에서만 접근"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("public-path-prefixes");
                });
    }

    @Test
    void prod에서는_api상위경로를_공개경로로_열수없다() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/api",
                        "trading.security.public-path-protection-mode=VPN",
                        "trading.security.public-path-protection-note=운영 API는 VPN 경로 뒤에서만 접근"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("/api");
                });
    }

    @Test
    void prod에서는_actuator하위경로도_공개경로로_열수없다() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/actuator/health",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=리버스 프록시에서 내부 헬스체크만 허용"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("/actuator/health");
                });
    }

    @Test
    void 공개경로를_열면_보호모드를_명시해야한다() {
        contextRunner
                .withPropertyValues("trading.security.public-path-prefixes[0]=/swagger-ui")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("public-path-protection-mode");
                });
    }

    @Test
    void 공개경로를_열면_보호메모를_남겨야한다() {
        contextRunner
                .withPropertyValues(
                        "trading.security.public-path-prefixes[0]=/swagger-ui",
                        "trading.security.public-path-protection-mode=VPN"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("public-path-protection-note");
                });
    }

    @Test
    void prod에서도_swagger문서경로는_보호선언이있으면_명시적으로_열수있다() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/swagger-ui",
                        "trading.security.public-path-prefixes[1]=/v3/api-docs",
                        "trading.security.public-path-protection-mode=VPN",
                        "trading.security.public-path-protection-note=사내 VPN 뒤에서만 Swagger 문서를 공개"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void nonProd에서도_보호선언이있으면_dashboard공개경로설정이_차단되지않는다() {
        contextRunner
                .withPropertyValues(
                        "trading.security.public-path-prefixes[0]=/api/dashboard",
                        "trading.security.public-path-protection-mode=PRIVATE_NETWORK",
                        "trading.security.public-path-protection-note=개발 사설망 내부 점검용"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TradingSecurityProps.class)
    static class SecurityPolicyConfig {

        @Bean
        SecurityPublicPathPolicyValidator securityPublicPathPolicyValidator(
                TradingSecurityProps securityProps,
                Environment environment
        ) {
            return new SecurityPublicPathPolicyValidator(securityProps, environment);
        }
    }
}
