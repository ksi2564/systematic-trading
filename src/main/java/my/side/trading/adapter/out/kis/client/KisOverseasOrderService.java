package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.KisOverseasCancelResponse;
import my.side.trading.adapter.out.kis.dto.OverseasOrderRequest;
import my.side.trading.adapter.out.kis.dto.OverseasOrderResponse;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisOverseasOrderService {

    private static final String DEFAULT_EXCHANGE = "NASD";

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final KisProps kisProps;
    private final KisOrderTrIdResolver trIdResolver;

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

    /**
     * 미국 주식 주문 취소 (해외주식 주문취소 API)
     */
    public KisOverseasCancelResponse cancelUsOrder(String orderNo) {
        String accessToken = kisAuthService.getAccessToken();
        String trId = trIdResolver.resolveCancelTrId();

        Map<String, String> requestBody = Map.of(
                "CANO", kisProps.cano(),
                "ACNT_PRDT_CD", kisProps.acntPrdtCd(),
                "OVRS_EXCG_CD", DEFAULT_EXCHANGE,
                "ORGN_ODNO", orderNo,
                "RVSE_CNCL_DVSN_CD", "02", // 02: 취소
                "ORD_QTY", "0", // 전량취소
                "OVRS_ORD_UNPR", "0");

        try {
            return kisWebClient.post()
                    .uri(KisOverseasOrderApiSpec.OVERSEAS_CANCEL_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("tr_id", trId)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("[KIS CANCEL ERROR] status={}, body={}",
                                        resp.statusCode(), body);
                                return Mono.error(new IllegalStateException(
                                        "[KIS CANCEL ERROR] status=%s, body=%s"
                                                .formatted(resp.statusCode(), body)));
                    }))
                    .bodyToMono(KisOverseasCancelResponse.class)
                    .block(kisProps.requestTimeout());
        } catch (WebClientResponseException e) {
            log.error("[KIS CANCEL EXCEPTION] status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    private OverseasOrderResponse placeUsOrder(OverseasOrderRequest request, boolean buy) {
        String accessToken = kisAuthService.getAccessToken();
        String trId = trIdResolver.resolveUsOrderTrId(buy ? ExecutionOrderSide.BUY : ExecutionOrderSide.SELL);

        try {
            return kisWebClient.post()
                    .uri(KisOverseasOrderApiSpec.OVERSEAS_ORDER_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("tr_id", trId)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("[KIS ORDER ERROR] status={}, body={}",
                                        resp.statusCode(), body);
                                return Mono.error(new IllegalStateException(
                                        "[KIS ORDER ERROR] status=%s, body=%s"
                                                .formatted(resp.statusCode(), body)));
                    }))
                    .bodyToMono(OverseasOrderResponse.class)
                    .block(kisProps.requestTimeout());
        } catch (WebClientResponseException e) {
            log.error("[KIS ORDER EXCEPTION] status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }
}
