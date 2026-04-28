package my.side.trading.adapter.out.kis.client;

import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasPeriodProfitResponse;
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

class KisOverseasPeriodProfitServiceTest {

    @Test
    void 실응답_기준_연속조회와_외화기준_쿼리를_사용한다() {
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
                          "output1": [
                            {
                              "trad_day": "20260204",
                              "ovrs_rlzt_pfls_amt": "-4.40000",
                              "stck_sll_tlex": "0.5400"
                            }
                          ],
                          "rt_cd": "0",
                          "msg_cd": "KIOK0510",
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
                      "output1": [
                        {
                          "trad_day": "20251218",
                          "ovrs_rlzt_pfls_amt": "-3.95500",
                          "stck_sll_tlex": "0.0000"
                        }
                      ],
                      "rt_cd": "0",
                      "msg_cd": "KIOK0510",
                      "msg1": "ok"
                    }
                    """
            ));
        };
        WebClient webClient = WebClient.builder()
                .baseUrl("https://example.test")
                .exchangeFunction(exchangeFunction)
                .build();
        KisAuthService authService = mock(KisAuthService.class);
        when(authService.getAccessToken()).thenReturn("ACCESS_TOKEN");

        KisOverseasPeriodProfitService service = new KisOverseasPeriodProfitService(
                webClient,
                authService,
                new KisProps("https://example.test", "appKey", "appSecret", "12345678-01", "12345678", "01", null, null)
        );

        KisOverseasPeriodProfitResponse response = service.getPeriodProfit(
                LocalDate.of(2025, 4, 14),
                LocalDate.of(2026, 4, 13)
        );

        assertThat(response).isNotNull();
        assertThat(response.items()).hasSize(2);
        assertThat(response.items())
                .extracting(KisOverseasPeriodProfitResponse.Item::tradeDate)
                .containsExactly("20260204", "20251218");

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).headers().getFirst("tr_cont")).isBlank();
        assertThat(requests.get(1).headers().getFirst("tr_cont")).isEqualTo("N");
        assertThat(requests.get(0).headers().getFirst("authorization")).isEqualTo("Bearer ACCESS_TOKEN");

        URI firstUri = requests.get(0).url();
        URI secondUri = requests.get(1).url();
        assertThat(firstUri.getQuery()).contains("WCRC_FRCR_DVSN_CD=01");
        assertThat(firstUri.getQuery()).contains("CTX_AREA_FK200=");
        assertThat(firstUri.getQuery()).contains("CTX_AREA_NK200=");
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
}
