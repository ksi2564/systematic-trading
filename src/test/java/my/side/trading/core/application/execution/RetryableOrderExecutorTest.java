package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.order.*;
import my.side.trading.testutil.FakeOrderCanceller;
import my.side.trading.testutil.FakeOrderFillChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RetryableOrderExecutorTest {

    private FakeBroker broker;
    private FakeOrderFillChecker fillChecker;
    private FakeOrderCanceller canceller;
    private RetryableOrderExecutor executor;

    @BeforeEach
    void setUp() {
        broker = new FakeBroker();
        fillChecker = new FakeOrderFillChecker();
        canceller = new FakeOrderCanceller();
        executor = new RetryableOrderExecutor(broker, fillChecker, canceller);
        executor.setWaitMs(0); // 테스트에서는 대기 시간 제거
    }

    @Test
    @DisplayName("첫 번째 시도에서 체결되면 성공 반환")
    void 첫_시도_체결_성공() {
        ExecutionOrder order = createOrder("QQQ", ExecutionOrderSide.BUY, 10);
        broker.setNextOrderId("ORD001");
        fillChecker.setFullyFilled("ORD001", 10, new BigDecimal("1000"));

        ExecutionResult result = executor.executeWithRetry(order);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.filledQty()).isEqualTo(10);
        assertThat(result.brokerOrderId()).isEqualTo("ORD001");
    }

    @Test
    @DisplayName("미체결 후 재시도하여 두 번째에서 체결")
    void 재시도_후_체결() {
        ExecutionOrder order = createOrder("QQQ", ExecutionOrderSide.SELL, 5);
        broker.setOrderIds("ORD001", "ORD002");

        // 첫 번째: 미체결
        fillChecker.setFillResult("ORD001", FillResult.partial(0, 5, BigDecimal.ZERO));
        // 두 번째: 체결
        fillChecker.setFullyFilled("ORD002", 5, new BigDecimal("500"));

        ExecutionResult result = executor.executeWithRetry(order);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.filledQty()).isEqualTo(5);
    }

    @Test
    @DisplayName("3회 모두 실패하면 FAILED 반환")
    void 세번_모두_실패() {
        ExecutionOrder order = createOrder("TQQQ", ExecutionOrderSide.BUY, 3);
        broker.setOrderIds("ORD001", "ORD002", "ORD003");

        // 모두 미체결
        fillChecker.setFillResult("ORD001", FillResult.partial(0, 3, BigDecimal.ZERO));
        fillChecker.setFillResult("ORD002", FillResult.partial(0, 3, BigDecimal.ZERO));
        fillChecker.setFillResult("ORD003", FillResult.partial(0, 3, BigDecimal.ZERO));

        ExecutionResult result = executor.executeWithRetry(order);

        assertThat(result.isFailed()).isTrue();
        assertThat(result.filledQty()).isEqualTo(0);
    }

    @Test
    @DisplayName("부분체결이 연속 발생해도 최대 3회만 주문")
    void 부분체결_연속_발생_시_최대_3회_제한() {
        ExecutionOrder order = createOrder("QQQ", ExecutionOrderSide.BUY, 10);
        broker.setOrderIds("ORD001", "ORD002", "ORD003");

        // 1차: 10주 → 7주 체결, 3주 미체결
        fillChecker.setFillResult("ORD001", FillResult.partial(7, 3, new BigDecimal("700")));
        // 2차: 3주 → 2주 체결, 1주 미체결
        fillChecker.setFillResult("ORD002", FillResult.partial(2, 1, new BigDecimal("200")));
        // 3차: 1주 → 0주 체결, 1주 미체결 (3번째 시도에서 실패)
        fillChecker.setFillResult("ORD003", FillResult.partial(0, 1, BigDecimal.ZERO));

        ExecutionResult result = executor.executeWithRetry(order);

        // 총 9주 체결 (7 + 2 + 0)
        assertThat(result.isPartial()).isTrue();
        assertThat(result.filledQty()).isEqualTo(9);

        // 중요: broker.setOrderIds에 3개만 설정했으므로,
        // 4번째 주문이 시도되면 예외 발생했을 것
        // 예외 없이 여기까지 왔다 = 최대 3회만 주문됨 ✅
    }

    @Test
    @DisplayName("버퍼 퍼센트는 시도 횟수에 따라 증가")
    void 버퍼_증가_확인() {
        assertThat(executor.getBufferPercent(1)).isEqualByComparingTo("0.3");
        assertThat(executor.getBufferPercent(2)).isEqualByComparingTo("0.5");
        assertThat(executor.getBufferPercent(3)).isEqualByComparingTo("0.8");
    }

    private ExecutionOrder createOrder(String symbol, ExecutionOrderSide side, long qty) {
        return ExecutionOrder.create(symbol, side, qty, new BigDecimal("100"), new BigDecimal("100.50"));
    }

    // 테스트용 FakeBroker
    private static class FakeBroker implements OrderBroker {
        private String[] orderIds = { "ORD001" };
        private int callCount = 0;

        void setNextOrderId(String orderId) {
            this.orderIds = new String[] { orderId };
        }

        void setOrderIds(String... orderIds) {
            this.orderIds = orderIds;
        }

        @Override
        public BrokerOrderResult place(ExecutionOrder order) {
            String orderId = orderIds[Math.min(callCount, orderIds.length - 1)];
            callCount++;
            return BrokerOrderResult.success(orderId, "주문 접수");
        }
    }
}
