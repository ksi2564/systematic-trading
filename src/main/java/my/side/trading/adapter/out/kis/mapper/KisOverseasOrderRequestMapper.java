package my.side.trading.adapter.out.kis.mapper;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.OverseasOrderRequest;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;

@Component
@RequiredArgsConstructor
public class KisOverseasOrderRequestMapper {

    private final KisProps kisProps;

    public OverseasOrderRequest toRequest(ExecutionOrder order) {
        return new OverseasOrderRequest(
                kisProps.cano(),
                kisProps.acntPrdtCd(),
                resolveExchangeCode(order.getSymbol()),
                order.getSymbol(),
                String.valueOf(order.getQuantity()),
                formatPrice(order.getLimitPrice()),
                "0",
                "00"
        );
    }

    private String resolveExchangeCode(String symbol) {
        // TODO: QLD는 AMEX인지 NASD인지 확인 / QQQ, QQQM, TQQQ는 NASD 맞음
        return "NASD";
    }

    private String formatPrice(java.math.BigDecimal price) {
        return price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
