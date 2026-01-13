package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasCcnlResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class KisOverseasCcnlService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps props;

    public KisOverseasCcnlResponse inquireCcnl(String ovrsExcgCd, String pdno, LocalDate localDate) {
        String accessToken = kisAuthService.getAccessToken();

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
                        .queryParam("CTX_AREA_NK200", "")
                        .queryParam("CTX_AREA_FK200", "")
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "TTTS3035R")
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisOverseasCcnlResponse.class)
                .block();
    }
}
