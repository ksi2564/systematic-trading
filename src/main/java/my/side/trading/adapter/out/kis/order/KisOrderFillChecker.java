package my.side.trading.adapter.out.kis.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisOverseasCcnlService;
import my.side.trading.adapter.out.kis.dto.KisOverseasCcnlResponse;
import my.side.trading.core.domain.execution.order.FillResult;
import my.side.trading.core.domain.execution.order.OrderFillChecker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * KIS API를 통한 체결 상태 조회 어댑터
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisOrderFillChecker implements OrderFillChecker {

    private static final String DEFAULT_EXCHANGE = "NASD";

    private final KisOverseasCcnlService ccnlService;

    @Override
    public FillResult checkFill(String brokerOrderId, String symbol) {
        try {
            KisOverseasCcnlResponse response = ccnlService.inquireCcnl(DEFAULT_EXCHANGE, symbol, LocalDate.now());

            if (response == null || !"0".equals(response.resultCode()) || response.output() == null) {
                log.warn("체결조회 실패: brokerOrderId={}, symbol={}, response={}",
                        brokerOrderId, symbol, response);
                return FillResult.notFound();
            }

            Optional<KisOverseasCcnlResponse.Item> item = response.output().stream()
                    .filter(i -> brokerOrderId.equals(i.orderNo()))
                    .findFirst();

            if (item.isEmpty()) {
                log.debug("주문번호 미발견: brokerOrderId={}, symbol={}", brokerOrderId, symbol);
                return FillResult.notFound();
            }

            KisOverseasCcnlResponse.Item order = item.get();
            long filledQty = parseLong(order.filledQty());
            long unfilledQty = parseLong(order.unfilledQty());
            BigDecimal orderPrice = parseBigDecimal(order.orderPrice());
            BigDecimal filledAmount = orderPrice.multiply(BigDecimal.valueOf(filledQty));

            boolean fullyFilled = unfilledQty == 0 && filledQty > 0;

            log.info("체결조회 결과: brokerOrderId={}, symbol={}, filledQty={}, unfilledQty={}, fullyFilled={}",
                    brokerOrderId, symbol, filledQty, unfilledQty, fullyFilled);

            if (fullyFilled) {
                return FillResult.full(filledQty, filledAmount);
            } else {
                return FillResult.partial(filledQty, unfilledQty, filledAmount);
            }

        } catch (Exception e) {
            log.error("체결조회 중 예외 발생: brokerOrderId={}, symbol={}", brokerOrderId, symbol, e);
            return FillResult.notFound();
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank())
            return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank())
            return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
