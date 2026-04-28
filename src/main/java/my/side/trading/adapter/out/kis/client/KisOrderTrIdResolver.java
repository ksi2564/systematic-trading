package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KisOrderTrIdResolver {

    private final KisProps kisProps;

    public String resolveUsOrderTrId(ExecutionOrderSide side) {
        boolean virtual = isVirtualTrading();
        return switch (side) {
            case BUY -> virtual ? "VTTT1002U" : "TTTT1002U";
            case SELL -> virtual ? "VTTT1001U" : "TTTT1006U";
        };
    }

    public String resolveCancelTrId() {
        return isVirtualTrading() ? "VTTT1004U" : "TTTT1004U";
    }

    private boolean isVirtualTrading() {
        String baseUrl = kisProps.baseUrl();
        return baseUrl != null && baseUrl.contains("openapivts");
    }
}
