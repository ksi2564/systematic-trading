package my.side.trading.adapter.out.kis.order;

import my.side.trading.adapter.out.kis.client.KisOverseasCcnlService;
import my.side.trading.adapter.out.kis.client.KisOverseasNccsService;
import my.side.trading.adapter.out.kis.dto.KisOverseasCcnlResponse;
import my.side.trading.adapter.out.kis.dto.KisOverseasNccsResponse;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private MarketCalendarService marketCalendarService;
    private KisOrderInquiry inquiry;

    @BeforeEach
    void setUp() {
        nccsService = mock(KisOverseasNccsService.class);
        ccnlService = mock(KisOverseasCcnlService.class);
        marketCalendarService = mock(MarketCalendarService.class);
        when(marketCalendarService.currentMarketDate()).thenReturn(LocalDate.of(2025, 12, 22));
        inquiry = new KisOrderInquiry(nccsService, ccnlService, marketCalendarService);
    }

    @Test
    void brokerOrderId가_있으면_주문체결내역_주문번호를_우선_확인한다() {
        ExecutionOrder order = confirmationRequiredOrder("QQQ", ExecutionOrderSide.BUY, 3, "OD123");
        when(ccnlService.inquireCcnl(eq("NASD"), eq("QQQ"), any(LocalDate.class), eq("OD123")))
                .thenReturn(ccnl(
                        item("OD122", "QQQ", "02", "3"),
                        item("OD123", "QQQ", "02", "3")));

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
    void 요청시장시각이_있으면_전후_5분_후보만_찾는다() {
        ExecutionOrder order = confirmationRequiredOrder(
                "QQQ",
                ExecutionOrderSide.BUY,
                3,
                null,
                LocalDateTime.of(2025, 12, 21, 23, 45));
        when(nccsService.inquireNccs("NASD"))
                .thenReturn(nccs(
                        nccsItem("OLD", "QQQ", "02", "3", "20251221", "233900"),
                        nccsItem("OD126", "QQQ", "02", "3", "20251221", "234900")));

        OrderInquiryResult result = inquiry.confirm(order);

        assertThat(result.status()).isEqualTo(OrderInquiryResult.Status.FOUND);
        assertThat(result.brokerOrderId()).isEqualTo("OD126");
    }

    @Test
    void 요청시장시각이_있고_window_밖이면_후보로_보지_않는다() {
        ExecutionOrder order = confirmationRequiredOrder(
                "QQQ",
                ExecutionOrderSide.BUY,
                3,
                null,
                LocalDateTime.of(2025, 12, 21, 23, 45));
        when(nccsService.inquireNccs("NASD"))
                .thenReturn(nccs(nccsItem("OD126", "QQQ", "02", "3", "20251221", "235100")));
        when(ccnlService.inquireCcnl(eq("NASD"), eq("QQQ"), eq(LocalDate.of(2025, 12, 21))))
                .thenReturn(ccnl());

        OrderInquiryResult result = inquiry.confirm(order);

        assertThat(result.status()).isEqualTo(OrderInquiryResult.Status.NOT_FOUND);
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
        verify(ccnlService).inquireCcnl("NASD", "QQQ", LocalDate.of(2025, 12, 22));
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
        return confirmationRequiredOrder(symbol, side, quantity, brokerOrderId, null);
    }

    private ExecutionOrder confirmationRequiredOrder(
            String symbol,
            ExecutionOrderSide side,
            long quantity,
            String brokerOrderId,
            LocalDateTime requestedMarketAt
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
                "timeout",
                requestedMarketAt);
    }

    private KisOverseasCcnlResponse ccnl(KisOverseasCcnlResponse.Item... items) {
        return new KisOverseasCcnlResponse("0", "OK", "정상", List.of(items));
    }

    private KisOverseasCcnlResponse.Item item(String orderNo, String symbol, String sideCode, String orderQty) {
        return new KisOverseasCcnlResponse.Item(orderNo, symbol, sideCode, orderQty, "100", "0", orderQty);
    }

    private KisOverseasNccsResponse nccs(KisOverseasNccsResponse.Item... items) {
        return new KisOverseasNccsResponse("0", "OK", "정상", List.of(items));
    }

    private KisOverseasNccsResponse.Item nccsItem(String orderNo, String symbol, String sideCode, String orderQty) {
        return nccsItem(orderNo, symbol, sideCode, orderQty, null, null);
    }

    private KisOverseasNccsResponse.Item nccsItem(
            String orderNo,
            String symbol,
            String sideCode,
            String orderQty,
            String orderDate,
            String orderTime
    ) {
        return new KisOverseasNccsResponse.Item(
                orderDate,
                orderNo,
                symbol,
                sideCode,
                sideCode.equals("02") ? "매수" : "매도",
                orderTime,
                orderQty,
                "0",
                orderQty,
                "100",
                "0",
                "0");
    }
}
