package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasPeriodProfitResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class KisOverseasPeriodProfitService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String INITIAL_TR_CONT = "";
    private static final String NEXT_TR_CONT = "N";

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps props;

    public KisOverseasPeriodProfitResponse getPeriodProfit(LocalDate startDate, LocalDate endDate) {
        String accessToken = kisAuthService.getAccessToken();
        List<KisOverseasPeriodProfitResponse.Item> allItems = new ArrayList<>();
        String ctxAreaFk200 = "";
        String ctxAreaNk200 = "";
        String trCont = INITIAL_TR_CONT;
        KisOverseasPeriodProfitResponse lastResponse = null;

        while (true) {
            PageResult page = fetchPage(accessToken, startDate, endDate, ctxAreaFk200, ctxAreaNk200, trCont);
            lastResponse = page.response();
            if (lastResponse == null || !"0".equals(lastResponse.resultCode())) {
                return lastResponse;
            }

            allItems.addAll(lastResponse.items());

            if (isLastPage(page.trCont())) {
                return lastResponse.withItems(List.copyOf(allItems));
            }

            String nextCtxAreaFk200 = lastResponse.nextCtxAreaFk200();
            String nextCtxAreaNk200 = lastResponse.nextCtxAreaNk200();
            if (nextCtxAreaFk200.isBlank() && nextCtxAreaNk200.isBlank()) {
                return lastResponse.withItems(List.copyOf(allItems));
            }
            if (nextCtxAreaFk200.equals(ctxAreaFk200) && nextCtxAreaNk200.equals(ctxAreaNk200)) {
                return lastResponse.withItems(List.copyOf(allItems));
            }

            ctxAreaFk200 = nextCtxAreaFk200;
            ctxAreaNk200 = nextCtxAreaNk200;
            trCont = NEXT_TR_CONT;
        }
    }

    private PageResult fetchPage(
            String accessToken,
            LocalDate startDate,
            LocalDate endDate,
            String ctxAreaFk200,
            String ctxAreaNk200,
            String trCont
    ) {
        return kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/overseas-stock/v1/trading/inquire-period-profit")
                        .queryParam("CANO", props.cano())
                        .queryParam("ACNT_PRDT_CD", props.acntPrdtCd())
                        .queryParam("OVRS_EXCG_CD", "")
                        .queryParam("NATN_CD", "")
                        .queryParam("CRCY_CD", "")
                        .queryParam("PDNO", "")
                        .queryParam("INQR_STRT_DT", startDate.format(YYYYMMDD))
                        .queryParam("INQR_END_DT", endDate.format(YYYYMMDD))
                        .queryParam("WCRC_FRCR_DVSN_CD", "01")
                        .queryParam("SORT_DVSN", "02")
                        .queryParam("CBLC_DVSN", "00")
                        .queryParam("CTX_AREA_FK200", ctxAreaFk200)
                        .queryParam("CTX_AREA_NK200", ctxAreaNk200)
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "TTTS3039R")
                .header("custtype", "P")
                .header("tr_cont", trCont)
                .exchangeToMono(response -> response.bodyToMono(KisOverseasPeriodProfitResponse.class)
                        .map(body -> new PageResult(extractTrCont(response), body)))
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

    private record PageResult(String trCont, KisOverseasPeriodProfitResponse response) {
    }
}
