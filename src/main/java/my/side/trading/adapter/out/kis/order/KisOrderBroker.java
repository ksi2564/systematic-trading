package my.side.trading.adapter.out.kis.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisOverseasOrderService;
import my.side.trading.adapter.out.kis.dto.OverseasOrderRequest;
import my.side.trading.adapter.out.kis.dto.OverseasOrderResponse;
import my.side.trading.adapter.out.kis.mapper.KisOverseasOrderRequestMapper;
import my.side.trading.core.domain.execution.order.BrokerOrderResult;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.OrderBroker;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@Component("kisOrderBroker")
@RequiredArgsConstructor
public class KisOrderBroker implements OrderBroker {

    private final KisOverseasOrderService kisOrderService;
    private final KisOverseasOrderRequestMapper requestMapper;

    @Override
    public BrokerOrderResult place(ExecutionOrder order) {
        OverseasOrderRequest req = requestMapper.toRequest(order);

        try {
            OverseasOrderResponse resp = (order.getSide() == ExecutionOrderSide.BUY)
                    ? kisOrderService.placeUsBuyOrder(req)
                    : kisOrderService.placeUsSellOrder(req);

            if (isSuccess(resp)) {
                String brokerOrderId = (resp.output() != null) ? resp.output().orderNo() : null;
                String msg = buildSuccessMessage(resp);
                return BrokerOrderResult.success(brokerOrderId, msg);
            }

            String brokerOrderId = (resp != null && resp.output() != null) ? resp.output().orderNo() : null;
            return BrokerOrderResult.failure(brokerOrderId, buildFailureMessage(resp));

        } catch (WebClientResponseException e) {
            // HTTP 레벨 에러(4xx/5xx)
            String body = e.getResponseBodyAsString();
            return BrokerOrderResult.failure(null, "HTTP " + e.getStatusCode() + " body=" + body);
        } catch (Exception e) {
            return BrokerOrderResult.failure(null, "Exception: " + e.getMessage());
        }
    }

    private boolean isSuccess(OverseasOrderResponse resp) {
        return resp != null && "0".equals(resp.resultCode());
    }

    private String buildSuccessMessage(OverseasOrderResponse resp) {
        if (resp == null) return "null response";
        String msg = resp.message();
        OverseasOrderResponse.Output out = resp.output();
        if (out == null) return msg;
        return "msg=" + msg + ", orderTime=" + out.orderTime() + ", orgNo=" + out.orgNo();
    }

    private String buildFailureMessage(OverseasOrderResponse resp) {
        if (resp == null) return "null response";
        return "rt_cd=" + resp.resultCode()
                + ", msg_cd=" + resp.messageCode()
                + ", msg=" + resp.message();
    }
}
