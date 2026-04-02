package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.execution.order.*;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.infrastructure.config.TradingExecutionProps;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.testutil.FakeExecutionJobRepository;
import my.side.trading.testutil.FakeOrderBroker;
import my.side.trading.testutil.FakeOrderCanceller;
import my.side.trading.testutil.FakeOrderFillChecker;
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
        private FakeOrderFillChecker fillChecker;
        private FakeOrderCanceller canceller;
        private FakeOrderInquiry orderInquiry;
        private ExecutionGuard guard;
        private ExecutionJobExecutor executor;

        @BeforeEach
        void setUp() {
                jobRepository = new FakeExecutionJobRepository();
                orderBroker = new FakeOrderBroker();
                fillChecker = new FakeOrderFillChecker();
                canceller = new FakeOrderCanceller();
                orderInquiry = new FakeOrderInquiry();
                guard = new ExecutionGuard(
                                new TradingExecutionProps(true),
                                new TradingOperationProps(
                                                OperatingMode.AUTO_LIVE,
                                                new TradingOperationProps.AutoLiveGateProps(5, true, true, true)),
                                () -> false);

                RetryableOrderExecutor retryableExecutor = new RetryableOrderExecutor(orderBroker, fillChecker,
                                canceller);
                retryableExecutor.setWaitMs(0); // 테스트에서는 대기 시간 제거

                executor = new ExecutionJobExecutor(jobRepository, retryableExecutor, orderInquiry, guard);
        }

        @Test
        void 주문요청_성공응답_및_전량체결_시_주문을_ACCEPTED_처리() {
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
                fillChecker.setFullyFilled("0123456789", 1, new BigDecimal("100"));

                LocalDateTime now = LocalDateTime.of(2025, 12, 21, 23, 45);
                ExecutionJob executed = executor.execute(1L, now, ExecutionTriggerType.AUTOMATED);

                ExecutionOrder executedOrder = executed.getOrders().get(0);
                assertThat(executedOrder.getBrokerOrderId()).isEqualTo("0123456789");
                assertThat(executedOrder.getStatus()).isEqualTo(ExecutionOrderStatus.ACCEPTED);
                assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void 부분체결_후_재시도로_전량체결_시_ACCEPTED_처리() {
                ExecutionOrder order = ExecutionOrder.rehydrate(
                                1L,
                                "QQQ",
                                ExecutionOrderSide.BUY,
                                10,
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
                // 첫 번째 시도: 7주 체결, 3주 미체결
                orderBroker.willReturnSequence(1L,
                                BrokerOrderResult.success("ORD001", "ok"),
                                BrokerOrderResult.success("ORD002", "ok"));
                fillChecker.setFillResult("ORD001", FillResult.partial(7, 3, new BigDecimal("700")));
                fillChecker.setFullyFilled("ORD002", 3, new BigDecimal("300"));

                LocalDateTime now = LocalDateTime.of(2025, 12, 21, 23, 45);
                ExecutionJob executed = executor.execute(1L, now, ExecutionTriggerType.AUTOMATED);

                ExecutionOrder executedOrder = executed.getOrders().get(0);
                assertThat(executedOrder.getStatus()).isEqualTo(ExecutionOrderStatus.ACCEPTED);
                assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        void 세번_재시도_모두_실패_시_REJECTED_처리() {
                ExecutionOrder order = ExecutionOrder.rehydrate(
                                1L,
                                "QQQ",
                                ExecutionOrderSide.BUY,
                                10,
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
                // 3회 모두 미체결
                orderBroker.willReturnSequence(1L,
                                BrokerOrderResult.success("ORD001", "ok"),
                                BrokerOrderResult.success("ORD002", "ok"),
                                BrokerOrderResult.success("ORD003", "ok"));
                fillChecker.setFillResult("ORD001", FillResult.partial(0, 10, BigDecimal.ZERO));
                fillChecker.setFillResult("ORD002", FillResult.partial(0, 10, BigDecimal.ZERO));
                fillChecker.setFillResult("ORD003", FillResult.partial(0, 10, BigDecimal.ZERO));

                LocalDateTime now = LocalDateTime.of(2025, 12, 21, 23, 45);
                ExecutionJob executed = executor.execute(1L, now, ExecutionTriggerType.AUTOMATED);

                ExecutionOrder executedOrder = executed.getOrders().get(0);
                assertThat(executedOrder.getStatus()).isEqualTo(ExecutionOrderStatus.REJECTED);
                assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        }

        @Test
        void SELL_주문이_BUY_주문보다_먼저_실행됨() {
                ExecutionOrder sellOrder = ExecutionOrder.rehydrate(
                                1L, "TQQQ", ExecutionOrderSide.SELL, 5,
                                new BigDecimal("100"), new BigDecimal("100"),
                                ExecutionOrderStatus.PLANNED, null, null);
                ExecutionOrder buyOrder = ExecutionOrder.rehydrate(
                                2L, "QQQ", ExecutionOrderSide.BUY, 10,
                                new BigDecimal("100"), new BigDecimal("100"),
                                ExecutionOrderStatus.PLANNED, null, null);

                ExecutionJob job = ExecutionJob.rehydrate(
                                1L,
                                LocalDate.of(2025, 12, 21),
                                LocalDateTime.of(2025, 12, 21, 23, 45),
                                ExecutionStatus.PENDING,
                                List.of(buyOrder, sellOrder), // BUY가 먼저 리스트에 있지만
                                null, null);

                jobRepository.save(job);
                orderBroker.willReturn(1L, BrokerOrderResult.success("SELL_ORD", "ok"));
                orderBroker.willReturn(2L, BrokerOrderResult.success("BUY_ORD", "ok"));
                fillChecker.setFullyFilled("SELL_ORD", 5, new BigDecimal("500"));
                fillChecker.setFullyFilled("BUY_ORD", 10, new BigDecimal("1000"));

                LocalDateTime now = LocalDateTime.of(2025, 12, 21, 23, 45);
                ExecutionJob executed = executor.execute(1L, now, ExecutionTriggerType.AUTOMATED);

                // SELL(TQQQ)과 BUY(QQQ) 모두 ACCEPTED
                assertThat(executed.getOrders().stream()
                                .allMatch(o -> o.getStatus() == ExecutionOrderStatus.ACCEPTED)).isTrue();
                assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
}
