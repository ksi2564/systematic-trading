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
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.LinkedHashMap;

@Slf4j
@Component("kisOrderBroker")
@RequiredArgsConstructor
public class KisOrderBroker implements OrderBroker {

    private final KisOverseasOrderService kisOrderService;
    private final KisOverseasOrderRequestMapper requestMapper;
    private final OpsAlertPublisher opsAlertPublisher;

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
            String failureMessage = buildFailureMessage(resp);
            publishBrokerFailureAlert(order, "response", resp != null ? resp.messageCode() : null, failureMessage);
            return BrokerOrderResult.failure(brokerOrderId, failureMessage);

        } catch (WebClientResponseException e) {
            // HTTP 레벨 에러(4xx/5xx)
            String body = e.getResponseBodyAsString();
            String failureMessage = "HTTP " + e.getStatusCode() + " body=" + body;
            publishBrokerFailureAlert(order, "http", String.valueOf(e.getStatusCode().value()), failureMessage);
            return BrokerOrderResult.failure(null, failureMessage);
        } catch (Exception e) {
            String failureMessage = "Exception: " + e.getMessage();
            publishBrokerFailureAlert(order, "exception", e.getClass().getSimpleName(), failureMessage);
            return BrokerOrderResult.failure(null, failureMessage);
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

    private void publishBrokerFailureAlert(
            ExecutionOrder order,
            String failureKind,
            String failureCode,
            String failureMessage
    ) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("symbol", order.getSymbol());
        details.put("side", order.getSide().name());
        details.put("failureKind", failureKind);
        details.put("failureCode", failureCode == null ? "-" : failureCode);
        details.put("message", failureMessage == null ? "-" : failureMessage);
        opsAlertPublisher.publish(new OpsAlert(
                OpsAlertType.BROKER_API_FAILURE,
                OpsAlertSeverity.ERROR,
                "broker-api-failure:" + failureKind + ":" + (failureCode == null ? "-" : failureCode),
                "Broker order API call failed",
                details));
    }
}
