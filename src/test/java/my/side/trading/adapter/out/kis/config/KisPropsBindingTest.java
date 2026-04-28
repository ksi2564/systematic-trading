package my.side.trading.adapter.out.kis.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KisPropsBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KisPropsConfig.class);

    @Test
    void timeout_기본값을_적용한다() {
        contextRunner
                .withPropertyValues(
                        "kis.base-url=https://example.test",
                        "kis.app-key=app-key",
                        "kis.app-secret=app-secret",
                        "kis.account-no=12345678-01",
                        "kis.cano=12345678",
                        "kis.acnt-prdt-cd=01")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KisProps props = context.getBean(KisProps.class);
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
                });
    }

    @Test
    void timeout_설정값을_바인딩한다() {
        contextRunner
                .withPropertyValues(
                        "kis.base-url=https://example.test",
                        "kis.connect-timeout=1500ms",
                        "kis.request-timeout=7s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KisProps props = context.getBean(KisProps.class);
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofMillis(1500));
                    assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
                });
    }

    @Configuration
    @EnableConfigurationProperties(KisProps.class)
    static class KisPropsConfig {
    }
}
