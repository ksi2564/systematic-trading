package my.side.trading.core.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TradingOperationPropsBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OperationPropsConfig.class);

    @Test
    void discord_timeout_기본값을_적용한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            TradingOperationProps props = context.getBean(TradingOperationProps.class);
            assertThat(props.alerts().discord().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(props.alerts().discord().requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void discord_timeout_설정값을_바인딩한다() {
        contextRunner
                .withPropertyValues(
                        "trading.operation.alerts.enabled=true",
                        "trading.operation.alerts.discord.enabled=true",
                        "trading.operation.alerts.discord.webhook-url=https://discord.example.test/webhook",
                        "trading.operation.alerts.discord.min-severity=ERROR",
                        "trading.operation.alerts.discord.connect-timeout=1500ms",
                        "trading.operation.alerts.discord.request-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TradingOperationProps props = context.getBean(TradingOperationProps.class);
                    assertThat(props.alerts().discord().connectTimeout()).isEqualTo(Duration.ofMillis(1500));
                    assertThat(props.alerts().discord().requestTimeout()).isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Configuration
    @EnableConfigurationProperties(TradingOperationProps.class)
    static class OperationPropsConfig {
    }
}
