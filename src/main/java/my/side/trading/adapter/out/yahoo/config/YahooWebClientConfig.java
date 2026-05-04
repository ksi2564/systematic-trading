package my.side.trading.adapter.out.yahoo.config;

import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import my.side.trading.core.infrastructure.config.TradingFxProps;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@RequiredArgsConstructor
public class YahooWebClientConfig {

    static final String YAHOO_FINANCE_BASE_URL = "https://query1.finance.yahoo.com";

    private final TradingFxProps props;

    @Bean
    WebClient yahooWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(props.yahoo().connectTimeout().toMillis()))
                .responseTimeout(props.yahoo().requestTimeout());

        return WebClient.builder()
                .baseUrl(YAHOO_FINANCE_BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
