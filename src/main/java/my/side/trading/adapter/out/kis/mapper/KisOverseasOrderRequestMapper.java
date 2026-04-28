package my.side.trading.adapter.out.kis.mapper;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.adapter.out.kis.dto.OverseasOrderRequest;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@RequiredArgsConstructor
public class KisOverseasOrderRequestMapper {

    private final KisProps kisProps;

    public OverseasOrderRequest toRequest(ExecutionOrder order) {
        return toRequest(order.getSymbol(), order.getQuantity(), order.getLimitPrice());
    }

    public OverseasOrderRequest toRequest(String symbol, long quantity, BigDecimal limitPrice) {
        return new OverseasOrderRequest(
                kisProps.cano(),
                kisProps.acntPrdtCd(),
                resolveExchangeCode(symbol),
                symbol,
                String.valueOf(quantity),
                formatPrice(limitPrice),
                "0",
                "00"
        );
    }

    private String resolveExchangeCode(String symbol) {
        // TODO: QLD는 AMEX인지 NASD인지 확인
        return "NASD";
    }

    private String formatPrice(java.math.BigDecimal price) {
        return price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
