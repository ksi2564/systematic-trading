package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasPsAmountResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisOverseasPsAmountService {

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps props;

    /**
     * 해외주식 매수가능금액조회(v1_해외주식-014)
     *
     * @param ovrsExcgCd  거래소코드 (NASD/NYSE/AMEX 등)
     * @param itemCd      종목코드(symbol과 동일)
     * @param ovrsOrdUnpr 해외주문단가
     */
    public KisOverseasPsAmountResponse getPsAmount(String ovrsExcgCd, String itemCd, BigDecimal ovrsOrdUnpr) {
        String accessToken = kisAuthService.getAccessToken();

        return kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/overseas-stock/v1/trading/inquire-psamount")
                        .queryParam("CANO", props.cano())
                        .queryParam("ACNT_PRDT_CD", props.acntPrdtCd())
                        .queryParam("OVRS_EXCG_CD", ovrsExcgCd)
                        .queryParam("OVRS_ORD_UNPR", ovrsOrdUnpr.toString())
                        .queryParam("ITEM_CD", itemCd)
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "TTTS3007R") // 실전: TTTS3007R, 모의: VTTS3007R
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisOverseasPsAmountResponse.class)
                .block(props.requestTimeout());
    }
}
