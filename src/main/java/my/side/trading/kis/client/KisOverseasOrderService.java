package my.side.trading.kis.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.kis.config.KisProps;
import my.side.trading.kis.dto.OverseasOrderRequest;
import my.side.trading.kis.dto.OverseasOrderResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisOverseasOrderService {

    private static final String OVERSEAS_ORDER_PATH = "/uapi/overseas-stock/v1/trading/order";

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps kisProps;

    /**
     * 미국 주식 매수 주문 (해외주식 주문 API)
     */
    public OverseasOrderResponse placeUsBuyOrder(OverseasOrderRequest request) {
        return placeUsOrder(request, true);
    }

    /**
     * 미국 주식 매도 주문 (해외주식 주문 API)
     */
    public OverseasOrderResponse placeUsSellOrder(OverseasOrderRequest request) {
        return placeUsOrder(request, false);
    }

    private OverseasOrderResponse placeUsOrder(OverseasOrderRequest request, boolean buy) {
        String accessToken = kisAuthService.getAccessToken();
        String trId = resolveUsTrId(buy);

        try {
            return kisWebClient.post()
                    .uri(OVERSEAS_ORDER_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("tr_id", trId)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("[KIS ORDER ERROR] status={}, body={}",
                                                resp.statusCode(), body);
                                        return Mono.error(new IllegalStateException(
                                                "[KIS ORDER ERROR] status=%s, body=%s"
                                                        .formatted(resp.statusCode(), body)
                                        ));
                                    })
                    )
                    .bodyToMono(OverseasOrderResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("[KIS ORDER EXCEPTION] status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /**
     * baseUrl을 보고 실전/모의 구분 후, 미국 매수/매도 TR_ID 선택
     */
    private String resolveUsTrId(boolean buy) {
        String baseUrl = kisProps.baseUrl();
        boolean virtual = baseUrl != null && baseUrl.contains("openapivts");

        if (virtual) {
            // 모의투자 TR_ID
            return buy ? "VTTT1002U" : "VTTT1001U";
        } else {
            // 실전투자 TR_ID
            return buy ? "TTTT1002U" : "TTTT1006U";
        }
    }
}
