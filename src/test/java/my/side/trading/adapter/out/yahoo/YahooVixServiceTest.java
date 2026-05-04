package my.side.trading.adapter.out.yahoo;

import my.side.trading.core.infrastructure.config.TradingCircuitBreakerProps;
import my.side.trading.core.infrastructure.config.TradingFxProps;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class YahooVixServiceTest {

    @Test
    void 공통_WebClient로_VIX_현재가를_조회한다() {
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        WebClient webClient = webClient(request -> {
            requestedUrl.set(request.url().toString());
            return quoteResponse("""
                    "meta": {
                      "regularMarketPrice": 21.3400,
                      "exchangeTimezoneName": "UTC"
                    }
                    """);
        });
        YahooVixService service = new YahooVixService(webClient, circuitBreakerProps(), fxProps());

        assertThat(service.getVixPrice()).contains(new BigDecimal("21.3400"));
        assertThat(requestedUrl.get()).isEqualTo("https://query1.finance.yahoo.com/v8/finance/chart/%5EVIX");
    }

    @Test
    void 공통_WebClient로_QQQ_과거가격을_조회한다() {
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        WebClient webClient = webClient(request -> {
            requestedUrl.set(request.url().toString());
            return quoteResponse("""
                    "meta": {
                      "exchangeTimezoneName": "UTC"
                    },
                    "timestamp": [1775779200, 1775865600, 1775952000],
                    "indicators": {
                      "quote": [
                        {
                          "close": [481.1000, 482.2000, 483.3000]
                        }
                      ]
                    }
                    """);
        });
        YahooVixService service = new YahooVixService(webClient, circuitBreakerProps(), fxProps());

        List<BigDecimal> prices = service.getQqqHistoricalPrices(2);

        assertThat(prices).containsExactly(new BigDecimal("482.2000"), new BigDecimal("483.3000"));
        assertThat(requestedUrl.get()).isEqualTo("https://query1.finance.yahoo.com/v8/finance/chart/QQQ?range=1y&interval=1d");
    }

    @Test
    void VIX_응답이_비어있으면_empty를_반환한다() {
        WebClient webClient = webClient(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build()));
        YahooVixService service = new YahooVixService(webClient, circuitBreakerProps(), fxProps());

        assertThat(service.getVixPrice()).isEmpty();
    }

    @Test
    void QQQ_조회가_실패하면_빈_목록을_반환한다() {
        WebClient webClient = webClient(request -> Mono.error(new IllegalStateException("Yahoo down")));
        YahooVixService service = new YahooVixService(webClient, circuitBreakerProps(), fxProps());

        assertThat(service.getQqqHistoricalPrices(200)).isEmpty();
    }

    private WebClient webClient(ExchangeFunction exchangeFunction) {
        return WebClient.builder()
                .baseUrl("https://query1.finance.yahoo.com")
                .exchangeFunction(exchangeFunction)
                .build();
    }

    private Mono<ClientResponse> quoteResponse(String resultBody) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {
                          "chart": {
                            "result": [
                              {
                                %s
                              }
                            ],
                            "error": null
                          }
                        }
                        """.formatted(resultBody))
                .build());
    }

    private TradingCircuitBreakerProps circuitBreakerProps() {
        return new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
    }

    private TradingFxProps fxProps() {
        return new TradingFxProps(new TradingFxProps.YahooProps(
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofDays(365),
                1_200
        ));
    }
}
