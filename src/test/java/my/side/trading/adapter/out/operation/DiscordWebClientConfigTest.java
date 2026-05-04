package my.side.trading.adapter.out.operation;

import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordWebClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(TradingOperationProps.class, this::operationProps)
            .withUserConfiguration(DiscordWebClientConfig.class);

    @Test
    void discord_WebClient_Bean을_생성한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("discordWebClient");
            assertThat(context.getBean("discordWebClient", WebClient.class)).isNotNull();
        });
    }

    private TradingOperationProps operationProps() {
        return new TradingOperationProps(
                OperatingMode.AUTO_LIVE,
                new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                new TradingOperationProps.RiskLimitProps(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO),
                new TradingOperationProps.AlertsProps(
                        true,
                        30,
                        new TradingOperationProps.DiscordProps(
                                true,
                                "https://discord.example.test/webhook",
                                OpsAlertSeverity.ERROR,
                                Duration.ofMillis(1500),
                                Duration.ofSeconds(2))));
    }
}
