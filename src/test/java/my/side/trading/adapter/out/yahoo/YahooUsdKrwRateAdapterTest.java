package my.side.trading.adapter.out.yahoo;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class YahooUsdKrwRateAdapterTest {

    @Test
    void 현재환율은_짧은_TTL동안_네트워크_재호출없이_캐시를_사용한다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-15T00:00:00Z"));
        AtomicInteger requestCount = new AtomicInteger();
        WebClient webClient = webClient(() -> {
            requestCount.incrementAndGet();
            return currentRateResponse("1450.1000");
        });

        YahooUsdKrwRateAdapter adapter = new YahooUsdKrwRateAdapter(webClient, props(), clock);

        Optional<BigDecimal> first = adapter.getCurrentUsdKrwRate();
        clock.advance(Duration.ofSeconds(10));
        Optional<BigDecimal> second = adapter.getCurrentUsdKrwRate();

        assertThat(first).contains(new BigDecimal("1450.1000"));
        assertThat(second).contains(new BigDecimal("1450.1000"));
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void 현재환율조회가_실패하면_마지막_성공값으로_열화한다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-15T00:00:00Z"));
        AtomicReference<String> state = new AtomicReference<>("success");
        WebClient webClient = webClient(() -> {
            if ("success".equals(state.get())) {
                return currentRateResponse("1450.1000");
            }
            return Mono.error(new IllegalStateException("Yahoo down"));
        });

        YahooUsdKrwRateAdapter adapter = new YahooUsdKrwRateAdapter(webClient, props(), clock);

        assertThat(adapter.getCurrentUsdKrwRate()).contains(new BigDecimal("1450.1000"));

        state.set("failure");
        clock.advance(Duration.ofSeconds(31));

        assertThat(adapter.getCurrentUsdKrwRate()).contains(new BigDecimal("1450.1000"));
    }

    @Test
    void 마지막_성공값도_만료되면_unavailable로_남긴다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-15T00:00:00Z"));
        AtomicReference<String> state = new AtomicReference<>("success");
        WebClient webClient = webClient(() -> {
            if ("success".equals(state.get())) {
                return currentRateResponse("1450.1000");
            }
            return Mono.error(new IllegalStateException("Yahoo down"));
        });

        YahooUsdKrwRateAdapter adapter = new YahooUsdKrwRateAdapter(webClient, props(), clock);

        assertThat(adapter.getCurrentUsdKrwRate()).contains(new BigDecimal("1450.1000"));

        state.set("failure");
        clock.advance(Duration.ofMinutes(31));

        assertThat(adapter.getCurrentUsdKrwRate()).isEmpty();
    }

    @Test
    void 과거환율조회가_실패하면_요청구간의_캐시된_날짜만_반환한다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-15T00:00:00Z"));
        AtomicReference<String> state = new AtomicReference<>("success");
        WebClient webClient = webClient(() -> {
            if ("success".equals(state.get())) {
                return historicalRateResponse();
            }
            return Mono.error(new IllegalStateException("Yahoo down"));
        });

        YahooUsdKrwRateAdapter adapter = new YahooUsdKrwRateAdapter(webClient, props(), clock);

        Map<LocalDate, BigDecimal> first = adapter.readUsdKrwRates(
                LocalDate.of(2026, 4, 10),
                LocalDate.of(2026, 4, 11));
        state.set("failure");
        Map<LocalDate, BigDecimal> second = adapter.readUsdKrwRates(
                LocalDate.of(2026, 4, 10),
                LocalDate.of(2026, 4, 12));

        assertThat(first)
                .containsEntry(LocalDate.of(2026, 4, 10), new BigDecimal("1460.1000"))
                .containsEntry(LocalDate.of(2026, 4, 11), new BigDecimal("1462.3000"));
        assertThat(second)
                .containsEntry(LocalDate.of(2026, 4, 10), new BigDecimal("1460.1000"))
                .containsEntry(LocalDate.of(2026, 4, 11), new BigDecimal("1462.3000"));
        assertThat(second).doesNotContainKey(LocalDate.of(2026, 4, 12));
    }

    private TradingFxProps props() {
        return new TradingFxProps(new TradingFxProps.YahooProps(
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                Duration.ofSeconds(2),
                Duration.ofDays(365),
                1_200
        ));
    }

    private WebClient webClient(ResponseProvider responseProvider) {
        ExchangeFunction exchangeFunction = request -> responseProvider.response();
        return WebClient.builder()
                .baseUrl("https://query1.finance.yahoo.com")
                .exchangeFunction(exchangeFunction)
                .build();
    }

    private Mono<ClientResponse> currentRateResponse(String price) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {
                          "chart": {
                            "result": [
                              {
                                "meta": {
                                  "regularMarketPrice": %s,
                                  "exchangeTimezoneName": "UTC"
                                }
                              }
                            ],
                            "error": null
                          }
                        }
                        """.formatted(price))
                .build());
    }

    private Mono<ClientResponse> historicalRateResponse() {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {
                          "chart": {
                            "result": [
                              {
                                "meta": {
                                  "exchangeTimezoneName": "UTC"
                                },
                                "timestamp": [1775779200, 1775865600],
                                "indicators": {
                                  "quote": [
                                    {
                                      "close": [1460.1000, 1462.3000]
                                    }
                                  ]
                                }
                              }
                            ],
                            "error": null
                          }
                        }
                        """)
                .build());
    }

    @FunctionalInterface
    private interface ResponseProvider {
        Mono<ClientResponse> response();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zoneId;

        private MutableClock(Instant initialInstant) {
            this(initialInstant, ZoneOffset.UTC);
        }

        private MutableClock(Instant initialInstant, ZoneId zoneId) {
            this.instant = new AtomicReference<>(initialInstant);
            this.zoneId = zoneId;
        }

        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }
    }
}
