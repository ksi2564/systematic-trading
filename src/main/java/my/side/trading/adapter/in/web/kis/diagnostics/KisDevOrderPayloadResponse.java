package my.side.trading.adapter.in.web.kis.diagnostics;

import com.fasterxml.jackson.annotation.JsonProperty;
import my.side.trading.adapter.out.kis.dto.OverseasOrderRequest;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;

public record KisDevOrderPayloadResponse(
        String orderPath,
        @JsonProperty("tr_id")
        String trId,
        ExecutionOrderSide side,
        OverseasOrderRequest body,
        boolean willExecute
) {
}
