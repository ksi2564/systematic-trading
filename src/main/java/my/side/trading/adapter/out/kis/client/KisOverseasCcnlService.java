package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasCcnlResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class KisOverseasCcnlService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int QUERY_RETRY_COUNT = 2;
    private static final Duration QUERY_RETRY_BACKOFF = Duration.ofMillis(200);
    private static final String INITIAL_TR_CONT = "";
    private static final String NEXT_TR_CONT = "N";

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps props;

    public KisOverseasCcnlResponse inquireCcnl(String ovrsExcgCd, String pdno, LocalDate localDate) {
        return inquireCcnl(ovrsExcgCd, pdno, localDate, null);
    }

    public KisOverseasCcnlResponse inquireCcnl(String ovrsExcgCd, String pdno, LocalDate localDate, String brokerOrderId) {
        String accessToken = kisAuthService.getAccessToken();
        List<KisOverseasCcnlResponse.Item> allItems = new ArrayList<>();
        String ctxAreaFk200 = "";
        String ctxAreaNk200 = "";
        String trCont = INITIAL_TR_CONT;
        KisOverseasCcnlResponse lastResponse;

        while (true) {
            PageResult page = fetchPage(accessToken, ovrsExcgCd, pdno, localDate, ctxAreaFk200, ctxAreaNk200, trCont);
            lastResponse = page.response();
            if (lastResponse == null || !"0".equals(lastResponse.resultCode())) {
                return lastResponse;
            }

            if (lastResponse.output() != null) {
                allItems.addAll(lastResponse.output());
            }

            if (isLastPage(page.trCont())) {
                return lastResponse.withOutput(List.copyOf(allItems));
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
            String pdno,
            LocalDate localDate,
            String ctxAreaFk200,
            String ctxAreaNk200,
            String trCont
    ) {
        String ymd = localDate.format(YYYYMMDD);

        return kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/overseas-stock/v1/trading/inquire-ccnl")
                        .queryParam("CANO", props.cano())
                        .queryParam("ACNT_PRDT_CD", props.acntPrdtCd())
                        .queryParam("PDNO", pdno)                // 종목
                        .queryParam("ORD_STRT_DT", ymd)          // 현지기준(일단 당일)
                        .queryParam("ORD_END_DT", ymd)
                        .queryParam("SLL_BUY_DVSN", "00")        // 전체
                        .queryParam("CCLD_NCCS_DVSN", "00")      // 전체
                        .queryParam("OVRS_EXCG_CD", ovrsExcgCd)
                        .queryParam("SORT_SQN", "DS")
                        .queryParam("ORD_DT", "")
                        .queryParam("ORD_GNO_BRNO", "")
                        .queryParam("ODNO", "")
                        .queryParam("CTX_AREA_NK200", ctxAreaNk200)
                        .queryParam("CTX_AREA_FK200", ctxAreaFk200)
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "TTTS3035R")
                .header("custtype", "P")
                .header("tr_cont", trCont)
                .exchangeToMono(response -> response.bodyToMono(KisOverseasCcnlResponse.class)
                        .map(body -> new PageResult(extractTrCont(response), body)))
                .retryWhen(Retry.fixedDelay(QUERY_RETRY_COUNT, QUERY_RETRY_BACKOFF))
                .block(props.requestTimeout());
    }

    private String extractTrCont(ClientResponse response) {
        return response.headers().header("tr_cont").stream()
                .findFirst()
                .orElse("");
    }

    private boolean isLastPage(String trCont) {
        return "D".equalsIgnoreCase(trCont) || "E".equalsIgnoreCase(trCont);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record PageResult(String trCont, KisOverseasCcnlResponse response) {
    }
}
