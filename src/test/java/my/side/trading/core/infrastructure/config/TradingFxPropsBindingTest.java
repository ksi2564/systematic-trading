package my.side.trading.core.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TradingFxPropsBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FxPropsConfig.class);

    @Test
    void 기본값을_정상적으로_바인딩한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            TradingFxProps props = context.getBean(TradingFxProps.class);
            assertThat(props.yahoo().currentCacheTtl()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.yahoo().staleSuccessTtl()).isEqualTo(Duration.ofMinutes(30));
            assertThat(props.yahoo().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(props.yahoo().requestTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(props.yahoo().historyCacheTtl()).isEqualTo(Duration.ofDays(365));
            assertThat(props.yahoo().historyCacheMaximumSize()).isEqualTo(1_200L);
        });
    }

    @Test
    void 설정값을_바인딩하고_열화TTL을_최소_현재캐시TTL로_보정한다() {
        contextRunner
                .withPropertyValues(
                        "trading.fx.yahoo.current-cache-ttl=45s",
                        "trading.fx.yahoo.stale-success-ttl=5s",
                        "trading.fx.yahoo.connect-timeout=1500ms",
                        "trading.fx.yahoo.request-timeout=2s",
                        "trading.fx.yahoo.history-cache-ttl=30d",
                        "trading.fx.yahoo.history-cache-maximum-size=365")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TradingFxProps props = context.getBean(TradingFxProps.class);
                    assertThat(props.yahoo().currentCacheTtl()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(props.yahoo().staleSuccessTtl()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(props.yahoo().connectTimeout()).isEqualTo(Duration.ofMillis(1500));
                    assertThat(props.yahoo().requestTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(props.yahoo().historyCacheTtl()).isEqualTo(Duration.ofDays(30));
                    assertThat(props.yahoo().historyCacheMaximumSize()).isEqualTo(365L);
                });
    }

    @Configuration
    @EnableConfigurationProperties(TradingFxProps.class)
    static class FxPropsConfig {
    }
}
