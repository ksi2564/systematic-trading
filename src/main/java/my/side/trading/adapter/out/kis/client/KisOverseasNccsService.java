package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasNccsResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class KisOverseasNccsService {

    private static final int QUERY_RETRY_COUNT = 2;
    private static final Duration QUERY_RETRY_BACKOFF = Duration.ofMillis(200);
    private static final String INITIAL_TR_CONT = "";
    private static final String NEXT_TR_CONT = "N";

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps props;

    public KisOverseasNccsResponse inquireNccs(String ovrsExcgCd) {
        String accessToken = kisAuthService.getAccessToken();
        List<KisOverseasNccsResponse.Item> allItems = new ArrayList<>();
        String ctxAreaFk200 = "";
        String ctxAreaNk200 = "";
        String trCont = INITIAL_TR_CONT;
        KisOverseasNccsResponse lastResponse;

        while (true) {
            PageResult page = fetchPage(accessToken, ovrsExcgCd, ctxAreaFk200, ctxAreaNk200, trCont);
            lastResponse = page.response();
            if (lastResponse == null || !"0".equals(lastResponse.resultCode())) {
                return lastResponse;
            }

            if (lastResponse.output() != null) {
                allItems.addAll(lastResponse.output());
            }

            String nextCtxAreaFk200 = normalize(lastResponse.ctxAreaFk200());
            String nextCtxAreaNk200 = normalize(lastResponse.ctxAreaNk200());
            if (nextCtxAreaFk200.isBlank() && nextCtxAreaNk200.isBlank()) {
                return lastResponse.withOutput(List.copyOf(allItems));
            }
            if (nextCtxAreaFk200.equals(ctxAreaFk200) && nextCtxAreaNk200.equals(ctxAreaNk200)) {
                return lastResponse.withOutput(List.copyOf(allItems));
            }

            ctxAreaFk200 = nextCtxAreaFk200;
            ctxAreaNk200 = nextCtxAreaNk200;
            trCont = NEXT_TR_CONT;
        }
    }

    private PageResult fetchPage(
            String accessToken,
            String ovrsExcgCd,
            String ctxAreaFk200,
            String ctxAreaNk200,
            String trCont
    ) {
        return kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/overseas-stock/v1/trading/inquire-nccs")
                        .queryParam("CANO", props.cano())
                        .queryParam("ACNT_PRDT_CD", props.acntPrdtCd())
                        .queryParam("OVRS_EXCG_CD", ovrsExcgCd)
                        .queryParam("SORT_SQN", "DS")
                        .queryParam("CTX_AREA_FK200", ctxAreaFk200)
                        .queryParam("CTX_AREA_NK200", ctxAreaNk200)
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "TTTS3018R")
                .header("custtype", "P")
                .header("tr_cont", trCont)
                .exchangeToMono(response -> response.bodyToMono(KisOverseasNccsResponse.class)
                        .map(body -> new PageResult(extractTrCont(response), body)))
                .retryWhen(Retry.fixedDelay(QUERY_RETRY_COUNT, QUERY_RETRY_BACKOFF))
                .block(props.requestTimeout());
    }

    private String extractTrCont(ClientResponse response) {
        return response.headers().header("tr_cont").stream()
                .findFirst()
                .orElse("");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record PageResult(String trCont, KisOverseasNccsResponse response) {
    }
}
