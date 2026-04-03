package my.side.trading.core.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TradingSecurityPropsBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityPropsConfig.class);

    @Test
    void shouldFailWhenApiKeyMissing() {
        contextRunner
                .withPropertyValues("trading.security.public-path-prefixes[0]=/actuator")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining("field 'apiKey'");
                });
    }

    @Test
    void shouldFailWhenApiKeyBlank() {
        contextRunner
                .withPropertyValues(
                        "trading.security.api-key=",
                        "trading.security.public-path-prefixes[0]=/actuator")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining("field 'apiKey'");
                });
    }

    @Test
    void shouldBindWhenApiKeyPresent() {
        contextRunner
                .withPropertyValues(
                        "trading.security.api-key=test-key",
                        "trading.security.public-path-prefixes[0]=/actuator")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TradingSecurityProps props = context.getBean(TradingSecurityProps.class);
                    assertThat(props.apiKey()).isEqualTo("test-key");
                    assertThat(props.publicPathPrefixes()).containsExactly("/actuator");
                });
    }

    @Configuration
    @EnableConfigurationProperties(TradingSecurityProps.class)
    static class SecurityPropsConfig {
    }
}
