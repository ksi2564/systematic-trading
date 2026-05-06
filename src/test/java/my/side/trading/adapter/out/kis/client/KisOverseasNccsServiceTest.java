package my.side.trading.adapter.out.kis.client;

import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasNccsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KisOverseasNccsServiceTest {

    @Test
    void ctx_기준으로_미체결내역을_연속조회해_합산한다() {
        AtomicInteger callCount = new AtomicInteger();
        List<ClientRequest> requests = new ArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            requests.add(request);
            int index = callCount.getAndIncrement();
            if (index == 0) {
                return Mono.just(jsonResponse("""
                        {
                          "ctx_area_fk200": "NEXT_FK",
                          "ctx_area_nk200": "NEXT_NK",
                          "output": [
                            {
                              "ord_dt": "20251221",
                              "odno": "OD001",
                              "pdno": "QQQ",
                              "sll_buy_dvsn_cd": "02",
                              "ord_tmd": "234500",
                              "ft_ord_qty": "3"
                            }
                          ],
                          "rt_cd": "0",
                          "msg_cd": "OK",
                          "msg1": "ok"
                        }
                        """));
            }
            return Mono.just(jsonResponse("""
                    {
                      "ctx_area_fk200": "",
                      "ctx_area_nk200": "",
                      "output": [
                        {
                          "ord_dt": "20251221",
                          "odno": "OD002",
                          "pdno": "QQQ",
                          "sll_buy_dvsn_cd": "02",
                          "ord_tmd": "234900",
                          "ft_ord_qty": "3"
                        }
                      ],
                      "rt_cd": "0",
                      "msg_cd": "OK",
                      "msg1": "ok"
                    }
                    """));
        };
        KisAuthService authService = mock(KisAuthService.class);
        when(authService.getAccessToken()).thenReturn("ACCESS_TOKEN");
        KisOverseasNccsService service = new KisOverseasNccsService(
                WebClient.builder()
                        .baseUrl("https://example.test")
                        .exchangeFunction(exchangeFunction)
                        .build(),
                authService,
                kisProps());

        KisOverseasNccsResponse response = service.inquireNccs("NASD");

        assertThat(response.output()).hasSize(2);
        assertThat(response.output())
                .extracting(KisOverseasNccsResponse.Item::orderNo)
                .containsExactly("OD001", "OD002");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).headers().getFirst("tr_cont")).isBlank();
        assertThat(requests.get(1).headers().getFirst("tr_cont")).isEqualTo("N");

        URI secondUri = requests.get(1).url();
        assertThat(secondUri.getQuery()).contains("CTX_AREA_FK200=NEXT_FK");
        assertThat(secondUri.getQuery()).contains("CTX_AREA_NK200=NEXT_NK");
    }

    @Test
    void ctx가_반복되면_무한조회하지_않고_중단한다() {
        List<ClientRequest> requests = new ArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            requests.add(request);
            return Mono.just(jsonResponse("""
                    {
                      "ctx_area_fk200": "SAME_FK",
                      "ctx_area_nk200": "SAME_NK",
                      "output": [],
                      "rt_cd": "0",
                      "msg_cd": "OK",
                      "msg1": "ok"
                    }
                    """));
        };
        KisAuthService authService = mock(KisAuthService.class);
        when(authService.getAccessToken()).thenReturn("ACCESS_TOKEN");
        KisOverseasNccsService service = new KisOverseasNccsService(
                WebClient.builder()
                        .baseUrl("https://example.test")
                        .exchangeFunction(exchangeFunction)
                        .build(),
                authService,
                kisProps());

        service.inquireNccs("NASD");

        assertThat(requests).hasSize(2);
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private KisProps kisProps() {
        return new KisProps("https://example.test", "appKey", "appSecret", "12345678-01", "12345678", "01", null, null);
    }
}
