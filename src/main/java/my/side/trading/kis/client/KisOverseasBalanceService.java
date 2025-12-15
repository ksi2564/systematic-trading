package my.side.trading.kis.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import my.side.trading.kis.config.KisProps;
import my.side.trading.kis.dto.KisOverseasBalanceResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class KisOverseasBalanceService {

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps props;

    public KisOverseasBalanceResponse getOverseasBalance() {
        String accessToken = kisAuthService.getAccessToken();

        return kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/overseas-stock/v1/trading/inquire-present-balance")
                        .queryParam("CANO", props.cano())
                        .queryParam("ACNT_PRDT_CD", props.acntPrdtCd())
                        .queryParam("WCRC_FRCR_DVSN_CD", "02") // 01: 원화, 02: 외화
                        .queryParam("NATN_CD", "840")              // 미국
                        .queryParam("TR_MKET_CD", "00")            // 전체
                        .queryParam("INQR_DVSN_CD", "00")          // 조회구분
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "CTRP6504R") // 실전투자: CTRP6504R, 모의투자: VTRP6504R
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisOverseasBalanceResponse.class)
                .block();

    }
}
