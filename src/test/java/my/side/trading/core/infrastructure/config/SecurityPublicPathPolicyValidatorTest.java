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
    void shouldFailWhenDashboardPathIsExposedInProd() {
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
    void shouldFailWhenApiPrefixIsExposedInProd() {
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
    void shouldFailWhenActuatorSubPathIsExposedInProd() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/actuator/health",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=리버스 프록시 뒤에서만 헬스체크 허용",
                        "trading.security.public-read-allowed-origins[0]=https://portfolio.myservice.com",
                        "trading.security.public-trusted-proxy-ranges[0]=198.51.100.0/24"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("/actuator/health");
                });
    }

    @Test
    void shouldRequireProtectionModeWhenPublicPathIsConfigured() {
        contextRunner
                .withPropertyValues("trading.security.public-path-prefixes[0]=/swagger-ui")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("public-path-protection-mode");
                });
    }

    @Test
    void shouldRequireProtectionNoteWhenPublicPathIsConfigured() {
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
    void shouldAllowSwaggerDocsInProdWhenProtectionMetadataIsPresent() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/swagger-ui",
                        "trading.security.public-path-prefixes[1]=/v3/api-docs",
                        "trading.security.public-path-protection-mode=VPN",
                        "trading.security.public-path-protection-note=사내 VPN 뒤에서만 Swagger 문서를 공개",
                        "trading.security.public-read-allowed-origins[0]=https://docs.myservice.com"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shouldAllowDashboardPathOutsideProd() {
        contextRunner
                .withPropertyValues(
                        "trading.security.public-path-prefixes[0]=/api/dashboard",
                        "trading.security.public-path-protection-mode=PRIVATE_NETWORK",
                        "trading.security.public-path-protection-note=개발 사설망에서만 접근"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shouldAllowPublicApiInProdWithConcreteOriginsAndProxyRanges() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/public/api",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=공개 포트폴리오 API는 리버스 프록시 뒤에서만 노출",
                        "trading.security.public-read-allowed-origins[0]=https://portfolio.myservice.com",
                        "trading.security.public-trusted-proxy-ranges[0]=198.51.100.0/24"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shouldRequireTrustedProxyRangesInReverseProxyMode() {
        contextRunner
                .withPropertyValues(
                        "trading.security.public-path-prefixes[0]=/public/api",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=공개 API는 리버스 프록시 뒤에서만 노출"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("public-trusted-proxy-ranges");
                });
    }

    @Test
    void shouldRequirePublicReadOriginsInProdWhenPublicApiIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/public/api",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=공개 API는 리버스 프록시 뒤에서만 노출",
                        "trading.security.public-trusted-proxy-ranges[0]=198.51.100.0/24"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("public-read-allowed-origins");
                });
    }

    @Test
    void shouldRejectWildcardOriginInProd() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/public/api",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=공개 API는 리버스 프록시 뒤에서만 노출",
                        "trading.security.public-read-allowed-origins[0]=https://*.example.com",
                        "trading.security.public-trusted-proxy-ranges[0]=198.51.100.0/24"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("wildcard");
                });
    }

    @Test
    void shouldRejectNonHttpsOriginInProd() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/public/api",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=공개 API는 리버스 프록시 뒤에서만 노출",
                        "trading.security.public-read-allowed-origins[0]=http://portfolio.myservice.com",
                        "trading.security.public-trusted-proxy-ranges[0]=198.51.100.0/24"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("HTTPS origin");
                });
    }

    @Test
    void shouldRejectOriginWithPathInProd() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/public/api",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=공개 API는 리버스 프록시 뒤에서만 노출",
                        "trading.security.public-read-allowed-origins[0]=https://portfolio.myservice.com/app",
                        "trading.security.public-trusted-proxy-ranges[0]=198.51.100.0/24"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("path");
                });
    }

    @Test
    void shouldRejectSampleOriginHostInProd() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/public/api",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=공개 API는 리버스 프록시 뒤에서만 노출",
                        "trading.security.public-read-allowed-origins[0]=https://portfolio.example.com",
                        "trading.security.public-trusted-proxy-ranges[0]=198.51.100.0/24"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("example.com");
                });
    }

    @Test
    void shouldRejectSampleProxyRangeInProd() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/public/api",
                        "trading.security.public-path-protection-mode=REVERSE_PROXY",
                        "trading.security.public-path-protection-note=공개 API는 리버스 프록시 뒤에서만 노출",
                        "trading.security.public-read-allowed-origins[0]=https://portfolio.myservice.com",
                        "trading.security.public-trusted-proxy-ranges[0]=10.0.0.0/8"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("샘플 CIDR");
                });
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
