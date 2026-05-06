package my.side.trading.adapter.out.kis.order;

import my.side.trading.adapter.out.kis.client.KisOverseasCcnlService;
import my.side.trading.adapter.out.kis.client.KisOverseasNccsService;
import my.side.trading.adapter.out.kis.dto.KisOverseasCcnlResponse;
import my.side.trading.adapter.out.kis.dto.KisOverseasNccsResponse;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KisOrderInquiryTest {

    private KisOverseasNccsService nccsService;
    private KisOverseasCcnlService ccnlService;
    private KisOrderInquiry inquiry;

    @BeforeEach
    void setUp() {
        nccsService = mock(KisOverseasNccsService.class);
        ccnlService = mock(KisOverseasCcnlService.class);
        inquiry = new KisOrderInquiry(nccsService, ccnlService);
    }

    @Test
    void brokerOrderId가_있으면_주문체결내역_주문번호를_우선_확인한다() {
        ExecutionOrder order = confirmationRequiredOrder("QQQ", ExecutionOrderSide.BUY, 3, "OD123");
        when(ccnlService.inquireCcnl(eq("NASD"), eq("QQQ"), any(LocalDate.class), eq("OD123")))
                .thenReturn(ccnl(item("OD123", "QQQ", "02", "3")));

        OrderInquiryResult result = inquiry.confirm(order);

        assertThat(result.status()).isEqualTo(OrderInquiryResult.Status.FOUND);
        assertThat(result.brokerOrderId()).isEqualTo("OD123");
        verify(nccsService, never()).inquireNccs(any());
    }

    @Test
    void brokerOrderId가_없으면_미체결내역에서_symbol_side_quantity로_찾는다() {
        ExecutionOrder order = confirmationRequiredOrder("QQQ", ExecutionOrderSide.BUY, 3, null);
        when(nccsService.inquireNccs("NASD"))
                .thenReturn(nccs(nccsItem("OD124", "QQQ", "02", "3")));

        OrderInquiryResult result = inquiry.confirm(order);

        assertThat(result.status()).isEqualTo(OrderInquiryResult.Status.FOUND);
        assertThat(result.brokerOrderId()).isEqualTo("OD124");
        verify(ccnlService, never()).inquireCcnl(eq("NASD"), eq("QQQ"), any(LocalDate.class));
    }

    @Test
    void 미체결내역에_없으면_주문체결내역에서_후보를_찾는다() {
        ExecutionOrder order = confirmationRequiredOrder("QQQ", ExecutionOrderSide.SELL, 5, null);
        when(nccsService.inquireNccs("NASD"))
                .thenReturn(nccs(nccsItem("OD124", "QQQ", "02", "5")));
        when(ccnlService.inquireCcnl(eq("NASD"), eq("QQQ"), any(LocalDate.class)))
                .thenReturn(ccnl(item("OD125", "QQQ", "01", "5")));

        OrderInquiryResult result = inquiry.confirm(order);

        assertThat(result.status()).isEqualTo(OrderInquiryResult.Status.FOUND);
        assertThat(result.brokerOrderId()).isEqualTo("OD125");
    }

    @Test
    void 조회_예외는_조회실패로_반환한다() {
        ExecutionOrder order = confirmationRequiredOrder("QQQ", ExecutionOrderSide.BUY, 3, null);
        when(nccsService.inquireNccs("NASD")).thenThrow(new RuntimeException("timeout"));

        OrderInquiryResult result = inquiry.confirm(order);

        assertThat(result.status()).isEqualTo(OrderInquiryResult.Status.INQUIRY_FAILED);
        assertThat(result.brokerOrderId()).isNull();
    }

    private ExecutionOrder confirmationRequiredOrder(
            String symbol,
            ExecutionOrderSide side,
            long quantity,
            String brokerOrderId
    ) {
        return ExecutionOrder.rehydrate(
                1L,
                symbol,
                side,
                quantity,
                new BigDecimal("100"),
                new BigDecimal("100"),
                ExecutionOrderStatus.CONFIRMATION_REQUIRED,
                brokerOrderId,
                "timeout");
    }

    private KisOverseasCcnlResponse ccnl(KisOverseasCcnlResponse.Item item) {
        return new KisOverseasCcnlResponse("0", "OK", "정상", List.of(item));
    }

    private KisOverseasCcnlResponse.Item item(String orderNo, String symbol, String sideCode, String orderQty) {
        return new KisOverseasCcnlResponse.Item(orderNo, symbol, sideCode, orderQty, "100", "0", orderQty);
    }

    private KisOverseasNccsResponse nccs(KisOverseasNccsResponse.Item item) {
        return new KisOverseasNccsResponse("0", "OK", "정상", List.of(item));
    }

    private KisOverseasNccsResponse.Item nccsItem(String orderNo, String symbol, String sideCode, String orderQty) {
        return new KisOverseasNccsResponse.Item(
                orderNo,
                symbol,
                sideCode,
                sideCode.equals("02") ? "매수" : "매도",
                orderQty,
                "0",
                orderQty,
                "100",
                "0",
                "0");
    }
}
