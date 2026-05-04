package my.side.trading.adapter.out.operation;

import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@RequiredArgsConstructor
public class DiscordWebClientConfig {

    private final TradingOperationProps operationProps;

    @Bean
    WebClient discordWebClient() {
        TradingOperationProps.DiscordProps discord = operationProps.alerts().discord();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(discord.connectTimeout().toMillis()))
                .responseTimeout(discord.requestTimeout());

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
