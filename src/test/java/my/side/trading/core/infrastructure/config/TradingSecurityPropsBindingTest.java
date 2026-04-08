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
                    assertThat(props.publicPathProtectionMode()).isEqualTo(PublicPathProtectionMode.UNSPECIFIED);
                    assertThat(props.publicPathProtectionNote()).isEmpty();
                    assertThat(props.publicReadAllowedOrigins()).isEmpty();
                    assertThat(props.publicRateLimitPerMinute()).isEqualTo(60);
                    assertThat(props.publicCacheMaxAgeSeconds()).isEqualTo(300);
                });
    }

    @Test
    void shouldBindPublicPathProtectionMetadata() {
        contextRunner
                .withPropertyValues(
                        "trading.security.api-key=test-key",
                        "trading.security.public-path-prefixes[0]=/swagger-ui",
                        "trading.security.public-path-protection-mode=VPN",
                        "trading.security.public-path-protection-note=사내 VPN 뒤에서만 문서를 공개한다")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TradingSecurityProps props = context.getBean(TradingSecurityProps.class);
                    assertThat(props.publicPathProtectionMode()).isEqualTo(PublicPathProtectionMode.VPN);
                    assertThat(props.publicPathProtectionNote()).isEqualTo("사내 VPN 뒤에서만 문서를 공개한다");
                });
    }

    @Test
    void shouldBindPublicReadOptions() {
        contextRunner
                .withPropertyValues(
                        "trading.security.api-key=test-key",
                        "trading.security.public-read-allowed-origins[0]=https://portfolio.example.com",
                        "trading.security.public-read-allowed-origins[1]=https://cdn.example.com",
                        "trading.security.public-rate-limit-per-minute=120",
                        "trading.security.public-cache-max-age-seconds=600")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TradingSecurityProps props = context.getBean(TradingSecurityProps.class);
                    assertThat(props.publicReadAllowedOrigins())
                            .containsExactly("https://portfolio.example.com", "https://cdn.example.com");
                    assertThat(props.publicRateLimitPerMinute()).isEqualTo(120);
                    assertThat(props.publicCacheMaxAgeSeconds()).isEqualTo(600);
                });
    }

    @Configuration
    @EnableConfigurationProperties(TradingSecurityProps.class)
    static class SecurityPropsConfig {
    }
}
