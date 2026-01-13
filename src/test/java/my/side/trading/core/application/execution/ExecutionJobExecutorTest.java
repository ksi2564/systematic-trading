package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.order.*;
import my.side.trading.core.infrastructure.config.TradingExecutionProps;
import my.side.trading.testutil.FakeExecutionJobRepository;
import my.side.trading.testutil.FakeOrderBroker;
import my.side.trading.testutil.FakeOrderInquiry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionJobExecutorTest {

        private FakeExecutionJobRepository jobRepository;
        private FakeOrderBroker orderBroker;
        private FakeOrderInquiry orderInquiry;

        private ExecutionGuard guard;

        private ExecutionJobExecutor executor;

        @BeforeEach
        void setUp() {
                jobRepository = new FakeExecutionJobRepository();
                orderBroker = new FakeOrderBroker();
                orderInquiry = new FakeOrderInquiry();
                // execution.enabled=true, killSwitch=false
                guard = new ExecutionGuard(new TradingExecutionProps(true), () -> false);
                executor = new ExecutionJobExecutor(jobRepository, orderBroker, orderInquiry, guard);
        }

        @Test
        void 주문요청_성공응답_및_주문번호_존재_시_주문을_ACCEPTED_처리() {
                ExecutionOrder order = ExecutionOrder.rehydrate(
                                1L,
                                "QQQ",
                                ExecutionOrderSide.BUY,
                                1,
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                ExecutionOrderStatus.PLANNED,
                                null,
                                null);
                ExecutionJob job = ExecutionJob.rehydrate(
                                1L,
                                LocalDate.of(2025, 12, 21),
                                LocalDateTime.of(2025, 12, 21, 23, 45),
                                ExecutionStatus.PENDING,
                                List.of(order),
                                null,
                                null);

                jobRepository.save(job);
                orderBroker.willReturn(1L, BrokerOrderResult.success("0123456789", "ok"));

                LocalDateTime now = LocalDateTime.of(2025, 12, 21, 23, 45);
                ExecutionJob executed = executor.execute(1L, now);

                ExecutionOrder executedOrder = executed.getOrders().get(0);
                assertThat(executedOrder.getBrokerOrderId()).isEqualTo("0123456789");
                assertThat(executedOrder.getStatus()).isEqualTo(ExecutionOrderStatus.ACCEPTED);
                assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void 주문요청_성공응답이지만_주문번호_없을_시_주문내역에서_찾으면_주문을_ACCEPTED_처리() {
                ExecutionOrder order = ExecutionOrder.rehydrate(
                                1L,
                                "QQQ",
                                ExecutionOrderSide.BUY,
                                1,
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                ExecutionOrderStatus.PLANNED,
                                null,
                                null);
                ExecutionJob job = ExecutionJob.rehydrate(
                                1L,
                                LocalDate.of(2025, 12, 21),
                                LocalDateTime.of(2025, 12, 21, 23, 45),
                                ExecutionStatus.PENDING,
                                List.of(order),
                                null,
                                null);

                jobRepository.save(job);
                orderBroker.willReturn(1L, BrokerOrderResult.success(null, "ok"));
                orderInquiry.willReturn(1L, OrderInquiryResult.found("0123456789", "주문내역 조회됨"));

                LocalDateTime now = LocalDateTime.of(2025, 12, 21, 23, 45);
                ExecutionJob executed = executor.execute(1L, now);

                ExecutionOrder executedOrder = executed.getOrders().get(0);
                assertThat(executedOrder.getStatus()).isEqualTo(ExecutionOrderStatus.ACCEPTED);
                assertThat(executedOrder.getBrokerOrderId()).isEqualTo("0123456789");
                assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void 주문요청_성공이지만_주문번호가_없고_주문내역에서도_못찾으면_REQUESTED_유지_Job은_RUNNING_유지() {
                ExecutionOrder order = ExecutionOrder.rehydrate(
                                1L,
                                "QQQ",
                                ExecutionOrderSide.BUY,
                                1,
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                ExecutionOrderStatus.PLANNED,
                                null,
                                null);

                ExecutionJob job = ExecutionJob.rehydrate(
                                1L,
                                LocalDate.of(2025, 12, 21),
                                LocalDateTime.of(2025, 12, 21, 23, 45),
                                ExecutionStatus.PENDING,
                                List.of(order),
                                null,
                                null);

                jobRepository.save(job);
                orderBroker.willReturn(1L, BrokerOrderResult.success(null, "ok"));
                orderInquiry.willReturn(1L, OrderInquiryResult.notFound("not found"));

                LocalDateTime now = LocalDateTime.of(2025, 12, 21, 23, 45);

                ExecutionJob executed = executor.execute(1L, now);

                ExecutionOrder executedOrder = executed.getOrders().get(0);
                assertThat(executedOrder.getStatus()).isEqualTo(ExecutionOrderStatus.REQUESTED);
                assertThat(executedOrder.getBrokerOrderId()).isNull();

                // 아직 확정이 아니므로 Job은 종료되면 안 됨
                assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        }

        @Test
        void 주문요청_실패응답_시_주문을_REJECTED_처리() {
                ExecutionOrder order = ExecutionOrder.rehydrate(
                                1L,
                                "QQQ",
                                ExecutionOrderSide.BUY,
                                1,
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                ExecutionOrderStatus.PLANNED,
                                null,
                                null);
                ExecutionJob job = ExecutionJob.rehydrate(
                                1L,
                                LocalDate.of(2025, 12, 21),
                                LocalDateTime.of(2025, 12, 21, 23, 45),
                                ExecutionStatus.PENDING,
                                List.of(order),
                                null,
                                null);

                jobRepository.save(job);
                orderBroker.willReturn(1L, BrokerOrderResult.failure(null, "fail"));

                LocalDateTime now = LocalDateTime.of(2025, 12, 21, 23, 45);
                ExecutionJob executed = executor.execute(1L, now);

                ExecutionOrder executedOrder = executed.getOrders().get(0);
                assertThat(executedOrder.getStatus()).isEqualTo(ExecutionOrderStatus.REJECTED);
                assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        }
}
