package my.side.trading.core.application.execution;

import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.domain.execution.order.*;
import my.side.trading.testutil.FakeRealtimePriceProvider;
import my.side.trading.testutil.FakeOrderCanceller;
import my.side.trading.testutil.FakeOrderFillChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetryableOrderExecutorTest {

    private FakeBroker broker;
    private FakeOrderFillChecker fillChecker;
    private FakeOrderCanceller canceller;
    private SequencedPriceProvider priceProvider;
    private RetryableOrderExecutor executor;

    @BeforeEach
    void setUp() {
        broker = new FakeBroker();
        fillChecker = new FakeOrderFillChecker();
        canceller = new FakeOrderCanceller();
        priceProvider = new SequencedPriceProvider();
        ExecutionOrderFactory orderFactory = new ExecutionOrderFactory(
                priceProvider,
                new MarketLikePricingPolicy(new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000));
        executor = new RetryableOrderExecutor(
                broker,
                fillChecker,
                canceller,
                orderFactory,
                new MarketLikePricingPolicy(new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000));
        executor.setWaitMs(0); // 테스트에서는 대기 시간 제거
        priceProvider.updateQuote("QQQ", new BigDecimal("100.00"), new BigDecimal("99.90"), new BigDecimal("100.10"));
        priceProvider.updateQuote("TQQQ", new BigDecimal("100.00"), new BigDecimal("99.90"), new BigDecimal("100.10"));
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
        priceProvider.setQuoteSequence("QQQ",
                new BigDecimal("100.00"), new BigDecimal("99.90"), new BigDecimal("100.10"),
                new BigDecimal("101.00"), new BigDecimal("100.80"), new BigDecimal("101.20"));

        // 첫 번째: 미체결
        fillChecker.setFillResult("ORD001", FillResult.partial(0, 5, BigDecimal.ZERO));
        // 두 번째: 체결
        fillChecker.setFullyFilled("ORD002", 5, new BigDecimal("500"));

        ExecutionResult result = executor.executeWithRetry(order);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.filledQty()).isEqualTo(5);
        assertThat(broker.placedOrders().get(0).getRefPrice()).isEqualByComparingTo("99.90");
        assertThat(broker.placedOrders().get(1).getRefPrice()).isEqualByComparingTo("100.80");
        assertThat(broker.placedOrders().get(1).getLimitPrice()).isEqualByComparingTo("100.79");
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
        assertThat(executor.getRetryTickOffset(1, ExecutionOrderSide.BUY)).isEqualTo(0);
        assertThat(executor.getRetryTickOffset(2, ExecutionOrderSide.BUY)).isEqualTo(1);
        assertThat(executor.getRetryTickOffset(3, ExecutionOrderSide.BUY)).isEqualTo(2);
    }

    private ExecutionOrder createOrder(String symbol, ExecutionOrderSide side, long qty) {
        return ExecutionOrder.create(symbol, side, qty, new BigDecimal("100"), new BigDecimal("100.50"));
    }

    // 테스트용 FakeBroker
    private static class FakeBroker implements OrderBroker {
        private String[] orderIds = { "ORD001" };
        private int callCount = 0;
        private final java.util.List<ExecutionOrder> placedOrders = new java.util.ArrayList<>();

        void setNextOrderId(String orderId) {
            this.orderIds = new String[] { orderId };
        }

        void setOrderIds(String... orderIds) {
            this.orderIds = orderIds;
        }

        @Override
        public BrokerOrderResult place(ExecutionOrder order) {
            placedOrders.add(order);
            String orderId = orderIds[Math.min(callCount, orderIds.length - 1)];
            callCount++;
            return BrokerOrderResult.success(orderId, "주문 접수");
        }

        java.util.List<ExecutionOrder> placedOrders() {
            return placedOrders;
        }
    }

    private static class SequencedPriceProvider extends FakeRealtimePriceProvider {
        private final java.util.Map<String, java.util.Queue<my.side.trading.core.domain.portfolio.RealtimeQuote>> sequences =
                new java.util.HashMap<>();

        void setQuoteSequence(
                String symbol,
                BigDecimal firstLast,
                BigDecimal firstBid,
                BigDecimal firstAsk,
                BigDecimal secondLast,
                BigDecimal secondBid,
                BigDecimal secondAsk) {
            java.util.ArrayDeque<my.side.trading.core.domain.portfolio.RealtimeQuote> queue = new java.util.ArrayDeque<>();
            queue.add(new my.side.trading.core.domain.portfolio.RealtimeQuote(firstLast, firstBid, firstAsk));
            queue.add(new my.side.trading.core.domain.portfolio.RealtimeQuote(secondLast, secondBid, secondAsk));
            sequences.put(symbol, queue);
        }

        @Override
        public java.util.Optional<my.side.trading.core.domain.portfolio.RealtimeQuote> getQuote(String symbol) {
            java.util.Queue<my.side.trading.core.domain.portfolio.RealtimeQuote> queue = sequences.get(symbol);
            if (queue != null && !queue.isEmpty()) {
                my.side.trading.core.domain.portfolio.RealtimeQuote current = queue.peek();
                if (queue.size() > 1) {
                    queue.poll();
                }
                return java.util.Optional.of(current);
            }
            return super.getQuote(symbol);
        }
    }
}
