package my.side.trading.adapter.out.kis.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisOrderTrIdResolver;
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
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component("kisOrderBroker")
@RequiredArgsConstructor
public class KisOrderBroker implements OrderBroker {

    private final KisOverseasOrderService kisOrderService;
    private final KisOverseasOrderRequestMapper requestMapper;
    private final KisOrderTrIdResolver trIdResolver;
    private final OpsAlertPublisher opsAlertPublisher;

    @Override
    public BrokerOrderResult place(ExecutionOrder order) {
        OverseasOrderRequest req = requestMapper.toRequest(order);
        String trId = trIdResolver.resolveUsOrderTrId(order.getSide());

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
            publishBrokerFailureAlert(order, trId, brokerOrderId, "response", resp != null ? resp.messageCode() : null, failureMessage);
            return BrokerOrderResult.failure(brokerOrderId, failureMessage);

        } catch (WebClientResponseException e) {
            String failureMessage = "HTTP " + e.getStatusCode();
            publishBrokerFailureAlert(order, trId, null, "http", String.valueOf(e.getStatusCode().value()), failureMessage);
            return BrokerOrderResult.failure(null, failureMessage);
        } catch (Exception e) {
            String failureMessage = "Exception: " + e.getMessage();
            if (requiresConfirmation(e)) {
                publishBrokerFailureAlert(order, trId, null, "timeout_network", confirmationFailureCode(e), failureMessage);
                return BrokerOrderResult.confirmationRequired(null, failureMessage);
            }
            publishBrokerFailureAlert(order, trId, null, "exception", e.getClass().getSimpleName(), failureMessage);
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
            String trId,
            String brokerOrderId,
            String failureKind,
            String failureCode,
            String failureMessage
    ) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("symbol", order.getSymbol());
        details.put("side", order.getSide().name());
        details.put("tr_id", trId == null || trId.isBlank() ? "-" : trId);
        details.put("brokerOrderId", brokerOrderId == null || brokerOrderId.isBlank() ? "-" : brokerOrderId);
        details.put("failureKind", failureKind);
        details.put("failureCode", failureCode == null ? "-" : failureCode);
        details.put("message", sanitizeFailureMessage(failureMessage));
        opsAlertPublisher.publish(new OpsAlert(
                OpsAlertType.BROKER_API_FAILURE,
                OpsAlertSeverity.ERROR,
                "broker-api-failure:" + failureKind + ":" + (failureCode == null ? "-" : failureCode),
                "Broker order API call failed",
                details));
    }

    private boolean requiresConfirmation(Throwable e) {
        return findConfirmationCause(e) != null;
    }

    private String confirmationFailureCode(Throwable e) {
        Throwable cause = findConfirmationCause(e);
        return cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName();
    }

    private Throwable findConfirmationCause(Throwable e) {
        if (e instanceof WebClientResponseException) {
            return null;
        }
        if (e instanceof WebClientRequestException) {
            return e;
        }
        Throwable current = e;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof IOException) {
                return current;
            }
            String simpleName = current.getClass().getSimpleName();
            if (simpleName.contains("Timeout") || simpleName.equals("PrematureCloseException")) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    private String sanitizeFailureMessage(String failureMessage) {
        if (failureMessage == null || failureMessage.isBlank()) {
            return "-";
        }
        return failureMessage
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer ***")
                .replaceAll("(?i)(app[-_ ]?secret\\s*[:=]\\s*)[^,}\\s]+", "$1***")
                .replaceAll("(?i)(app[-_ ]?key\\s*[:=]\\s*)[^,}\\s]+", "$1***")
                .replaceAll("(?i)(CANO\\s*[:=]\\s*)[^,}\\s]+", "$1***")
                .replaceAll("(?i)(ACNT_PRDT_CD\\s*[:=]\\s*)[^,}\\s]+", "$1***");
    }
}
