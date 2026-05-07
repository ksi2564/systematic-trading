package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.ExecutionStatus;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;
import my.side.trading.testutil.FakeExecutionJobRepository;
import my.side.trading.testutil.FakeOrderInquiry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionOrderConfirmationServiceTest {

    private FakeExecutionJobRepository jobRepository;
    private FakeOrderInquiry orderInquiry;
    private ExecutionOrderConfirmationService service;

    @BeforeEach
    void setUp() {
        jobRepository = new FakeExecutionJobRepository();
        orderInquiry = new FakeOrderInquiry();
        service = new ExecutionOrderConfirmationService(jobRepository, orderInquiry);
    }

    @Test
    void 조회에서_찾으면_accepted로_해소하고_job을_재계산한다() {
        ExecutionJob job = failedJob(confirmationRequiredOrder(1L, "QQQ", null));
        jobRepository.save(job);
        orderInquiry.willReturn(1L, OrderInquiryResult.found("OD123", "주문체결내역 조회됨"));

        ExecutionOrderConfirmationResult result = service.confirm(
                1L,
                1L,
                Instant.parse("2026-05-06T12:00:00Z"));

        ExecutionJob saved = jobRepository.findById(1L).orElseThrow();
        ExecutionOrder savedOrder = saved.getOrders().get(0);
        assertThat(result.currentStatus()).isEqualTo(ExecutionOrderStatus.ACCEPTED);
        assertThat(result.inquiryStatus()).isEqualTo(OrderInquiryResult.Status.FOUND);
        assertThat(savedOrder.getBrokerOrderId()).isEqualTo("OD123");
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void 조회에서_못_찾으면_확인필요_상태를_유지한다() {
        ExecutionJob job = failedJob(confirmationRequiredOrder(1L, "QQQ", null));
        jobRepository.save(job);
        orderInquiry.willReturn(1L, OrderInquiryResult.notFound("조회 결과 없음"));

        ExecutionOrderConfirmationResult result = service.confirm(
                1L,
                1L,
                Instant.parse("2026-05-06T12:00:00Z"));

        ExecutionJob saved = jobRepository.findById(1L).orElseThrow();
        ExecutionOrder savedOrder = saved.getOrders().get(0);
        assertThat(result.currentStatus()).isEqualTo(ExecutionOrderStatus.CONFIRMATION_REQUIRED);
        assertThat(result.inquiryStatus()).isEqualTo(OrderInquiryResult.Status.NOT_FOUND);
        assertThat(savedOrder.getBrokerOrderId()).isNull();
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void 조회_실패도_확인필요_상태를_유지한다() {
        ExecutionJob job = failedJob(confirmationRequiredOrder(1L, "QQQ", null));
        jobRepository.save(job);
        orderInquiry.willReturn(1L, OrderInquiryResult.failed("timeout"));

        ExecutionOrderConfirmationResult result = service.confirm(
                1L,
                1L,
                Instant.parse("2026-05-06T12:00:00Z"));

        ExecutionJob saved = jobRepository.findById(1L).orElseThrow();
        assertThat(result.currentStatus()).isEqualTo(ExecutionOrderStatus.CONFIRMATION_REQUIRED);
        assertThat(result.inquiryStatus()).isEqualTo(OrderInquiryResult.Status.INQUIRY_FAILED);
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void 확인필요가_아닌_주문은_확인할_수_없다() {
        ExecutionJob job = failedJob(order(1L, "QQQ", ExecutionOrderStatus.ACCEPTED, "OD123"));
        jobRepository.save(job);

        assertThatThrownBy(() -> service.confirm(
                1L,
                1L,
                Instant.parse("2026-05-06T12:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMATION_REQUIRED");
    }

    private ExecutionJob failedJob(ExecutionOrder order) {
        return ExecutionJob.rehydrate(
                1L,
                LocalDate.of(2026, 5, 5),
                Instant.parse("2026-05-06T09:45:00Z"),
                ExecutionStatus.FAILED,
                List.of(order),
                Instant.parse("2026-05-06T09:45:00Z"),
                Instant.parse("2026-05-06T09:46:00Z"));
    }

    private ExecutionOrder confirmationRequiredOrder(Long id, String symbol, String brokerOrderId) {
        return order(id, symbol, ExecutionOrderStatus.CONFIRMATION_REQUIRED, brokerOrderId);
    }

    private ExecutionOrder order(Long id, String symbol, ExecutionOrderStatus status, String brokerOrderId) {
        return ExecutionOrder.rehydrate(
                id,
                symbol,
                ExecutionOrderSide.BUY,
                1,
                new BigDecimal("100"),
                new BigDecimal("100"),
                status,
                brokerOrderId,
                "message");
    }
}
