package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasPeriodProfitResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class KisOverseasPeriodProfitService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps props;

    public KisOverseasPeriodProfitResponse getPeriodProfit(LocalDate startDate, LocalDate endDate) {
        String accessToken = kisAuthService.getAccessToken();

        return kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/overseas-stock/v1/trading/inquire-period-profit")
                        .queryParam("CANO", props.cano())
                        .queryParam("ACNT_PRDT_CD", props.acntPrdtCd())
                        .queryParam("PDNO", "")
                        .queryParam("INQR_STRT_DT", startDate.format(YYYYMMDD))
                        .queryParam("INQR_END_DT", endDate.format(YYYYMMDD))
                        .queryParam("SORT_DVSN", "02")
                        .queryParam("CBLC_DVSN", "00")
                        .queryParam("CTX_AREA_NK100", "")
                        .queryParam("CTX_AREA_FK100", "")
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "TTTS3039R")
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisOverseasPeriodProfitResponse.class)
                .block();
    }
}
