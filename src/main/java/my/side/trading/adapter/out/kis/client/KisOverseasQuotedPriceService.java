package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.QuotedPriceResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class KisOverseasQuotedPriceService {

    private final WebClient webClient;
    private final KisAuthService kisAuthService;
    private final KisProps props;

    /**
     * 해외주식 현재 체결가(v1_해외주식-009)
     *
     * @param symbol
     * @return
     */
    public QuotedPriceResponse getQuotedPrice(String symbol) {
        String accessToken = kisAuthService.getAccessToken();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/overseas-price/v1/quotations/price")
                        .queryParam("AUTH", "")
                        .queryParam("EXCD", "NAS")
                        .queryParam("SYMB", symbol)
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "HHDFS00000300")
                .retrieve()
                .bodyToMono(QuotedPriceResponse.class)
                .block(props.requestTimeout());
    }
}
