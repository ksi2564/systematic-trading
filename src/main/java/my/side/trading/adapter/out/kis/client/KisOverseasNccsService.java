package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasNccsResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class KisOverseasNccsService {

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps props;

    public KisOverseasNccsResponse inquireNccs(String ovrsExcgCd) {
        String accessToken = kisAuthService.getAccessToken();

        return kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/overseas-stock/v1/trading/inquire-nccs")
                        .queryParam("CANO", props.cano())
                        .queryParam("ACNT_PRDT_CD", props.acntPrdtCd())
                        .queryParam("OVRS_EXCG_CD", ovrsExcgCd)
                        .queryParam("SORT_SQN", "DS")
                        .queryParam("CTX_AREA_FK200", "")
                        .queryParam("CTX_AREA_NK200", "")
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "TTTS3018R")
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisOverseasNccsResponse.class)
                .block(props.requestTimeout());
    }
}
