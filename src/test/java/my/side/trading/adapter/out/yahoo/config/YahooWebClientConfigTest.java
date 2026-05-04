package my.side.trading.adapter.out.yahoo.config;

import my.side.trading.core.infrastructure.config.TradingFxProps;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class YahooWebClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(TradingFxProps.class, () -> new TradingFxProps(new TradingFxProps.YahooProps(
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(30),
                    Duration.ofMillis(1500),
                    Duration.ofSeconds(2),
                    Duration.ofDays(365),
                    1_200
            )))
            .withUserConfiguration(YahooWebClientConfig.class);

    @Test
    void Yahoo_WebClient_Bean을_생성한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("yahooWebClient");
            assertThat(context.getBean("yahooWebClient", WebClient.class)).isNotNull();
        });
    }
}
