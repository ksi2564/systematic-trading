package my.side.trading.core.application.execution;

import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.domain.execution.order.BrokerOrderResult;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.FillResult;
import my.side.trading.core.domain.execution.order.OrderBroker;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.testutil.FakeExecutionJobRepository;
import my.side.trading.testutil.FakeOrderCanceller;
import my.side.trading.testutil.FakeOrderFillChecker;
import my.side.trading.testutil.FakeRealtimePriceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
        executor = createExecutor(BigDecimal.ZERO);
        executor.setWaitMs(0);
        priceProvider.updateQuote("QQQ", new BigDecimal("100.00"), new BigDecimal("99.90"), new BigDecimal("100.10"));
        priceProvider.updateQuote("TQQQ", new BigDecimal("100.00"), new BigDecimal("99.90"), new BigDecimal("100.10"));
    }

    @Test
    @DisplayName("첫 시도에서 체결되면 성공을 반환한다")
    void 첫_시도에서_체결되면_성공을_반환한다() {
        ExecutionOrder order = createOrder("QQQ", ExecutionOrderSide.BUY, 10);
        broker.setNextOrderId("ORD001");
        fillChecker.setFullyFilled("ORD001", 10, new BigDecimal("1000"));

        ExecutionResult result = executor.executeWithRetry(order);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.filledQty()).isEqualTo(10);
        assertThat(result.brokerOrderId()).isEqualTo("ORD001");
    }

    @Test
    @DisplayName("미체결분은 재시도하면서 최신 호가로 재계산한다")
    void 재시도하면서_최신_호가로_재계산한다() {
        ExecutionOrder order = createOrder("QQQ", ExecutionOrderSide.SELL, 5);
        broker.setOrderIds("ORD001", "ORD002");
        priceProvider.setQuoteSequence("QQQ",
                new BigDecimal("100.00"), new BigDecimal("99.90"), new BigDecimal("100.10"),
                new BigDecimal("101.00"), new BigDecimal("100.80"), new BigDecimal("101.20"));

        fillChecker.setFillResult("ORD001", FillResult.partial(0, 5, BigDecimal.ZERO));
        fillChecker.setFullyFilled("ORD002", 5, new BigDecimal("500"));

        ExecutionResult result = executor.executeWithRetry(order);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.filledQty()).isEqualTo(5);
        assertThat(broker.placedOrders().get(0).getRefPrice()).isEqualByComparingTo("99.90");
        assertThat(broker.placedOrders().get(1).getRefPrice()).isEqualByComparingTo("100.80");
        assertThat(broker.placedOrders().get(1).getLimitPrice()).isEqualByComparingTo("100.79");
    }

    @Test
    @DisplayName("모든 재시도가 실패하면 failed를 반환한다")
    void 모든_재시도가_실패하면_실패_상태를_반환한다() {
        ExecutionOrder order = createOrder("TQQQ", ExecutionOrderSide.BUY, 3);
        broker.setOrderIds("ORD001", "ORD002", "ORD003");

        fillChecker.setFillResult("ORD001", FillResult.partial(0, 3, BigDecimal.ZERO));
        fillChecker.setFillResult("ORD002", FillResult.partial(0, 3, BigDecimal.ZERO));
        fillChecker.setFillResult("ORD003", FillResult.partial(0, 3, BigDecimal.ZERO));

        ExecutionResult result = executor.executeWithRetry(order);

        assertThat(result.isFailed()).isTrue();
        assertThat(result.filledQty()).isEqualTo(0);
    }

    @Test
    @DisplayName("재시도 총 노출 한도를 넘기면 block 플래그와 함께 종료한다")
    void 재시도_노출_한도를_넘기면_차단_플래그와_함께_종료한다() {
        executor = createExecutor(new BigDecimal("250.00"));
        executor.setWaitMs(0);

        ExecutionOrder order = createOrder("QQQ", ExecutionOrderSide.BUY, 2);

        ExecutionResult result = executor.executeWithRetry(order);

        assertThat(result.isFailed()).isTrue();
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.blockReason()).isEqualTo(ExecutionBlockReason.RISK_LIMIT_BREACH);
        assertThat(result.detailMessage()).contains("RETRY_EXPOSURE");
    }

    @Test
    @DisplayName("주문 확인 필요 결과가 오면 추가 재시도를 중단한다")
    void 주문_확인_필요_결과가_오면_추가_재시도를_중단한다() {
        ExecutionOrder order = createOrder("QQQ", ExecutionOrderSide.BUY, 10);
        broker.setNextResult(BrokerOrderResult.confirmationRequired(null, "timeout"));

        ExecutionResult result = executor.executeWithRetry(order);

        assertThat(result.isFailed()).isTrue();
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.blockReason()).isEqualTo(ExecutionBlockReason.ORDER_CONFIRMATION_REQUIRED);
        assertThat(broker.placedOrders()).hasSize(1);
    }


    @Test
    @DisplayName("버퍼 tick 시퀀스는 시도 횟수에 따라 증가한다")
    void 버퍼_tick_시퀀스는_시도_횟수에_따라_증가한다() {
        assertThat(executor.getRetryTickOffset(1, ExecutionOrderSide.BUY)).isEqualTo(0);
        assertThat(executor.getRetryTickOffset(2, ExecutionOrderSide.BUY)).isEqualTo(1);
        assertThat(executor.getRetryTickOffset(3, ExecutionOrderSide.BUY)).isEqualTo(2);
    }

    private RetryableOrderExecutor createExecutor(BigDecimal maxRetryExposureUsd) {
        ExecutionOrderFactory orderFactory = new ExecutionOrderFactory(
                priceProvider,
                new MarketLikePricingPolicy(new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000));
        ExecutionRiskLimitService riskLimitService = new ExecutionRiskLimitService(
                new TradingOperationProps(
                        OperatingMode.AUTO_LIVE,
                        new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                        new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                        new TradingOperationProps.RiskLimitProps(
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                maxRetryExposureUsd,
                                BigDecimal.ZERO),
                        new TradingOperationProps.AlertsProps(false, 30, null)),
                new FakeExecutionJobRepository());
        return new RetryableOrderExecutor(
                broker,
                fillChecker,
                canceller,
                orderFactory,
                new MarketLikePricingPolicy(new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000),
                riskLimitService);
    }

    private ExecutionOrder createOrder(String symbol, ExecutionOrderSide side, long qty) {
        return ExecutionOrder.create(symbol, side, qty, new BigDecimal("100"), new BigDecimal("100.50"));
    }

    private static class FakeBroker implements OrderBroker {
        private String[] orderIds = { "ORD001" };
        private int callCount = 0;
        private BrokerOrderResult nextResult;
        private final java.util.List<ExecutionOrder> placedOrders = new java.util.ArrayList<>();

        void setNextOrderId(String orderId) {
            this.orderIds = new String[] { orderId };
        }

        void setOrderIds(String... orderIds) {
            this.orderIds = orderIds;
        }

        void setNextResult(BrokerOrderResult nextResult) {
            this.nextResult = nextResult;
        }

        @Override
        public BrokerOrderResult place(ExecutionOrder order) {
            placedOrders.add(order);
            if (nextResult != null) {
                callCount++;
                return nextResult;
            }
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
