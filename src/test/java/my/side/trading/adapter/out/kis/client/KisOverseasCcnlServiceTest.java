package my.side.trading.adapter.out.kis.client;

import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasCcnlResponse;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KisOverseasCcnlServiceTest {

    @Test
    void trCont와_ctx_기준으로_주문체결내역을_연속조회해_합산한다() {
        AtomicInteger callCount = new AtomicInteger();
        List<ClientRequest> requests = new ArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            requests.add(request);
            int index = callCount.getAndIncrement();
            if (index == 0) {
                return Mono.just(jsonResponse(
                        "M",
                        """
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
                        """
                ));
            }
            return Mono.just(jsonResponse(
                    "D",
                    """
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
                    """
            ));
        };
        KisAuthService authService = mock(KisAuthService.class);
        when(authService.getAccessToken()).thenReturn("ACCESS_TOKEN");
        KisOverseasCcnlService service = new KisOverseasCcnlService(
                WebClient.builder()
                        .baseUrl("https://example.test")
                        .exchangeFunction(exchangeFunction)
                        .build(),
                authService,
                kisProps());

        KisOverseasCcnlResponse response = service.inquireCcnl(
                "NASD",
                "QQQ",
                LocalDate.of(2025, 12, 21),
                "OD999");

        assertThat(response.output()).hasSize(2);
        assertThat(response.output())
                .extracting(KisOverseasCcnlResponse.Item::orderNo)
                .containsExactly("OD001", "OD002");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).headers().getFirst("tr_cont")).isBlank();
        assertThat(requests.get(1).headers().getFirst("tr_cont")).isEqualTo("N");

        URI firstUri = requests.get(0).url();
        URI secondUri = requests.get(1).url();
        assertThat(firstUri.getQuery()).contains("ODNO=");
        assertThat(firstUri.getQuery()).doesNotContain("OD999");
        assertThat(secondUri.getQuery()).contains("CTX_AREA_FK200=NEXT_FK");
        assertThat(secondUri.getQuery()).contains("CTX_AREA_NK200=NEXT_NK");
    }

    private ClientResponse jsonResponse(String trCont, String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("tr_cont", trCont)
                .body(body)
                .build();
    }

    private KisProps kisProps() {
        return new KisProps("https://example.test", "appKey", "appSecret", "12345678-01", "12345678", "01", null, null);
    }
}
