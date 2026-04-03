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
                        "trading.security.public-path-prefixes[0]=/api/dashboard"
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
                        "trading.security.public-path-prefixes[0]=/api"
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
                        "trading.security.public-path-prefixes[0]=/actuator/health"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("/actuator/health");
                });
    }

    @Test
    void prod에서도_swagger문서경로는_명시적으로_열수있다() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "trading.security.public-path-prefixes[0]=/swagger-ui",
                        "trading.security.public-path-prefixes[1]=/v3/api-docs"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void nonProd에서는_dashboard공개경로설정이_차단되지않는다() {
        contextRunner
                .withPropertyValues("trading.security.public-path-prefixes[0]=/api/dashboard")
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
