package my.side.trading.adapter.out.kis.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisOverseasOrderService;
import my.side.trading.adapter.out.kis.dto.KisOverseasCancelResponse;
import my.side.trading.core.domain.execution.order.CancelResult;
import my.side.trading.core.domain.execution.order.OrderCanceller;
import my.side.trading.shared.security.SensitiveDataSanitizer;
import org.springframework.stereotype.Component;

/**
 * KIS API를 통한 주문 취소 어댑터
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisOrderCanceller implements OrderCanceller {

    private final KisOverseasOrderService orderService;

    @Override
    public CancelResult cancel(String brokerOrderId, String symbol) {
        try {
            log.info("주문취소 요청: brokerOrderId={}, symbol={}", brokerOrderId, symbol);

            KisOverseasCancelResponse response = orderService.cancelUsOrder(brokerOrderId);

            if (response == null) {
                return CancelResult.failure("응답 없음");
            }

            if (response.isSuccess()) {
                log.info("주문취소 성공: brokerOrderId={}, symbol={}, message={}",
                        brokerOrderId, symbol, SensitiveDataSanitizer.sanitize(response.message()));
                return CancelResult.success(SensitiveDataSanitizer.sanitize(response.message()));
            } else {
                log.warn("주문취소 실패: brokerOrderId={}, symbol={}, code={}, message={}",
                        brokerOrderId, symbol, response.messageCode(), SensitiveDataSanitizer.sanitize(response.message()));
                return CancelResult.failure(SensitiveDataSanitizer.sanitize(response.message()));
            }

        } catch (Exception e) {
            String reason = SensitiveDataSanitizer.sanitizeThrowable(e);
            log.error("주문취소 중 예외 발생: brokerOrderId={}, symbol={}, reason={}", brokerOrderId, symbol, reason);
            return CancelResult.failure("예외: " + reason);
        }
    }
}
