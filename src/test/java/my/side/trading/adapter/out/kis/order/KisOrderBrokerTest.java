package my.side.trading.adapter.out.kis.order;

import my.side.trading.adapter.out.kis.client.KisOrderTrIdResolver;
import my.side.trading.adapter.out.kis.client.KisOverseasOrderService;
import my.side.trading.adapter.out.kis.dto.OverseasOrderRequest;
import my.side.trading.adapter.out.kis.dto.OverseasOrderResponse;
import my.side.trading.adapter.out.kis.mapper.KisOverseasOrderRequestMapper;
import my.side.trading.core.domain.execution.order.BrokerOrderResult;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KisOrderBrokerTest {

    private KisOverseasOrderService orderService;
    private KisOverseasOrderRequestMapper requestMapper;
    private KisOrderTrIdResolver trIdResolver;
    private OpsAlertPublisher alertPublisher;
    private KisOrderBroker broker;

    @BeforeEach
    void setUp() {
        orderService = mock(KisOverseasOrderService.class);
        requestMapper = mock(KisOverseasOrderRequestMapper.class);
        trIdResolver = mock(KisOrderTrIdResolver.class);
        alertPublisher = mock(OpsAlertPublisher.class);
        broker = new KisOrderBroker(orderService, requestMapper, trIdResolver, alertPublisher);

        when(requestMapper.toRequest(any())).thenReturn(new OverseasOrderRequest(
                "12345678",
                "01",
                "NASD",
                "QQQ",
                "1",
                "100.00",
                "0",
                "00"));
        when(trIdResolver.resolveUsOrderTrId(ExecutionOrderSide.BUY)).thenReturn("TTTT1002U");
    }

    @Test
    void timeout_예외는_확인_필요_결과와_알림을_남긴다() {
        ExecutionOrder order = order();
        when(orderService.placeUsBuyOrder(any()))
                .thenThrow(new RuntimeException(new SocketTimeoutException("read timed out")));

        BrokerOrderResult result = broker.place(order);

        assertThat(result.confirmationRequired()).isTrue();
        assertThat(result.success()).isFalse();

        OpsAlert alert = captureAlert();
        assertThat(alert.type()).isEqualTo(OpsAlertType.BROKER_API_FAILURE);
        assertThat(alert.details()).containsEntry("symbol", "QQQ");
        assertThat(alert.details()).containsEntry("side", "BUY");
        assertThat(alert.details()).containsEntry("tr_id", "TTTT1002U");
        assertThat(alert.details()).containsEntry("brokerOrderId", "-");
        assertThat(alert.details()).containsEntry("failureKind", "timeout_network");
        assertThat(alert.details()).containsEntry("failureCode", "SocketTimeoutException");
        assertThat(alert.details().toString()).doesNotContain("12345678");
    }

    @Test
    void kis_응답_실패는_일반_실패로_남긴다() {
        ExecutionOrder order = order();
        OverseasOrderResponse response = new OverseasOrderResponse(
                "1",
                "EGW00123",
                "주문 거부",
                new OverseasOrderResponse.Output("001", "OD123", "235959"));
        when(orderService.placeUsBuyOrder(any())).thenReturn(response);

        BrokerOrderResult result = broker.place(order);

        assertThat(result.type()).isEqualTo(BrokerOrderResult.ResultType.REJECTED);
        assertThat(result.confirmationRequired()).isFalse();
        assertThat(result.brokerOrderId()).isEqualTo("OD123");

        OpsAlert alert = captureAlert();
        assertThat(alert.details()).containsEntry("tr_id", "TTTT1002U");
        assertThat(alert.details()).containsEntry("brokerOrderId", "OD123");
        assertThat(alert.details()).containsEntry("failureKind", "response");
        assertThat(alert.details()).containsEntry("failureCode", "EGW00123");
    }

    private OpsAlert captureAlert() {
        ArgumentCaptor<OpsAlert> captor = ArgumentCaptor.forClass(OpsAlert.class);
        verify(alertPublisher).publish(captor.capture());
        return captor.getValue();
    }

    private ExecutionOrder order() {
        return ExecutionOrder.create(
                "QQQ",
                ExecutionOrderSide.BUY,
                1,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"));
    }
}
