package my.side.trading.core.application.execution;

import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.application.operation.OperationsKpiService;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.execution.order.BrokerOrderResult;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.ExecutionStatus;
import my.side.trading.core.domain.execution.order.FillResult;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeReader;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.infrastructure.config.TradingExecutionProps;
import my.side.trading.core.infrastructure.config.TradingMarketCalendarProps;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.testutil.FakeExecutionJobRepository;
import my.side.trading.testutil.FakeOrderBroker;
import my.side.trading.testutil.FakeOrderCanceller;
import my.side.trading.testutil.FakeOrderFillChecker;
import my.side.trading.testutil.FakeOrderInquiry;
import my.side.trading.testutil.FakeRealtimePriceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionJobExecutorTest {

    private FakeExecutionJobRepository jobRepository;
    private FakeOrderBroker orderBroker;
    private FakeOrderFillChecker fillChecker;
    private FakeOrderCanceller canceller;
    private FakeOrderInquiry orderInquiry;
    private ExecutionGuard guard;
    private ExecutionJobExecutor executor;
    private ExecutionRiskLimitService riskLimitService;
    private OpsAlertPublisher alertPublisher;
    private TradingMarketCalendarProps marketCalendarProps;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        jobRepository = new FakeExecutionJobRepository();
        orderBroker = new FakeOrderBroker();
        fillChecker = new FakeOrderFillChecker();
        canceller = new FakeOrderCanceller();
        orderInquiry = new FakeOrderInquiry();
        alertPublisher = mock(OpsAlertPublisher.class);
        marketCalendarProps = new TradingMarketCalendarProps("America/New_York", List.of(), List.of(), List.of());
        fixedClock = Clock.fixed(Instant.parse("2025-12-22T04:45:00Z"), ZoneOffset.UTC);

        OperationsKpiService operationsKpiService = mock(OperationsKpiService.class);
        when(operationsKpiService.hasAutoLiveBreach()).thenReturn(false);

        TradingOperationProps operationProps = new TradingOperationProps(
                OperatingMode.AUTO_LIVE,
                new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                new TradingOperationProps.RiskLimitProps(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO),
                new TradingOperationProps.AlertsProps(false, 30, null));
        OperatingModeReader operatingModeReader = new OperatingModeReader() {
            @Override
            public OperatingMode currentMode() {
                return OperatingMode.AUTO_LIVE;
            }

            @Override
            public boolean hasManualApprovalRecord() {
                return true;
            }
        };
        guard = new ExecutionGuard(
                new TradingExecutionProps(true),
                operationProps,
                operatingModeReader,
                () -> false,
                operationsKpiService,
                alert -> {});
        riskLimitService = new ExecutionRiskLimitService(operationProps, jobRepository);

        FakeRealtimePriceProvider priceProvider = FakeRealtimePriceProvider.withLastPrices(
                Map.of("QQQ", new BigDecimal("100.00"), "TQQQ", new BigDecimal("100.00")));
        ExecutionOrderFactory orderFactory = new ExecutionOrderFactory(
                priceProvider,
                new MarketLikePricingPolicy(new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000));

        RetryableOrderExecutor retryableExecutor = new RetryableOrderExecutor(
                orderBroker,
                fillChecker,
                canceller,
                orderFactory,
                new MarketLikePricingPolicy(new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000),
                riskLimitService);
        retryableExecutor.setWaitMs(0);

        executor = new ExecutionJobExecutor(
                jobRepository,
                retryableExecutor,
                orderInquiry,
                guard,
                riskLimitService,
                alertPublisher,
                marketCalendarProps,
                fixedClock);
    }

    @Test
    void 주문이_완전_체결되면_accepted로_저장한다() {
        ExecutionJob job = singleOrderJob(order(1L, "QQQ", ExecutionOrderSide.BUY, 1));

        jobRepository.save(job);
        orderBroker.willReturn(1L, BrokerOrderResult.success("0123456789", "ok"));
        fillChecker.setFullyFilled("0123456789", 1, new BigDecimal("100"));

        ExecutionJob executed = executor.execute(1L, LocalDateTime.of(2025, 12, 21, 23, 45), ExecutionTriggerType.AUTOMATED);

        assertThat(executed.getOrders().get(0).getBrokerOrderId()).isEqualTo("0123456789");
        assertThat(executed.getOrders().get(0).getStatus()).isEqualTo(ExecutionOrderStatus.ACCEPTED);
        assertThat(executed.getOrders().get(0).getRequestedMarketAt())
                .isEqualTo(LocalDateTime.of(2025, 12, 21, 23, 45));
        assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void 재시도_후_체결되면_accepted로_저장한다() {
        ExecutionJob job = singleOrderJob(order(1L, "QQQ", ExecutionOrderSide.BUY, 10));

        jobRepository.save(job);
        orderBroker.willReturnSequence(1L,
                BrokerOrderResult.success("ORD001", "ok"),
                BrokerOrderResult.success("ORD002", "ok"));
        fillChecker.setFillResult("ORD001", FillResult.partial(7, 3, new BigDecimal("700")));
        fillChecker.setFullyFilled("ORD002", 3, new BigDecimal("300"));

        ExecutionJob executed = executor.execute(1L, LocalDateTime.of(2025, 12, 21, 23, 45), ExecutionTriggerType.AUTOMATED);

        assertThat(executed.getOrders().get(0).getStatus()).isEqualTo(ExecutionOrderStatus.ACCEPTED);
        assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void 부분_체결로_끝나면_미정리_주문_알림을_발행한다() {
        ExecutionJob job = singleOrderJob(order(1L, "QQQ", ExecutionOrderSide.BUY, 10));

        jobRepository.save(job);
        orderBroker.willReturnSequence(1L,
                BrokerOrderResult.success("ORD001", "ok"),
                BrokerOrderResult.success("ORD002", "ok"),
                BrokerOrderResult.success("ORD003", "ok"));
        fillChecker.setFillResult("ORD001", FillResult.partial(3, 7, new BigDecimal("300")));
        fillChecker.setFillResult("ORD002", FillResult.partial(0, 7, BigDecimal.ZERO));
        fillChecker.setFillResult("ORD003", FillResult.partial(0, 7, BigDecimal.ZERO));

        ExecutionJob executed = executor.execute(1L, LocalDateTime.of(2025, 12, 21, 23, 45), ExecutionTriggerType.AUTOMATED);

        assertThat(executed.getOrders().get(0).getStatus()).isEqualTo(ExecutionOrderStatus.ACCEPTED);
        assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(alertPublisher).publish(argThat(alert -> alert.type() == OpsAlertType.UNRESOLVED_ORDER));
    }

    @Test
    void 모든_재시도가_실패하면_rejected로_저장한다() {
        ExecutionJob job = singleOrderJob(order(1L, "QQQ", ExecutionOrderSide.BUY, 10));

        jobRepository.save(job);
        orderBroker.willReturnSequence(1L,
                BrokerOrderResult.success("ORD001", "ok"),
                BrokerOrderResult.success("ORD002", "ok"),
                BrokerOrderResult.success("ORD003", "ok"));
        fillChecker.setFillResult("ORD001", FillResult.partial(0, 10, BigDecimal.ZERO));
        fillChecker.setFillResult("ORD002", FillResult.partial(0, 10, BigDecimal.ZERO));
        fillChecker.setFillResult("ORD003", FillResult.partial(0, 10, BigDecimal.ZERO));

        ExecutionJob executed = executor.execute(1L, LocalDateTime.of(2025, 12, 21, 23, 45), ExecutionTriggerType.AUTOMATED);

        assertThat(executed.getOrders().get(0).getStatus()).isEqualTo(ExecutionOrderStatus.REJECTED);
        assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void 주문_확인_필요가_발생하면_현재_주문을_확인필요로_남기고_나머지는_skip한다() {
        ExecutionOrder firstOrder = order(1L, "QQQ", ExecutionOrderSide.BUY, 10);
        ExecutionOrder secondOrder = order(2L, "TQQQ", ExecutionOrderSide.BUY, 10);
        ExecutionJob job = ExecutionJob.rehydrate(
                1L,
                LocalDate.of(2025, 12, 21),
                LocalDateTime.of(2025, 12, 21, 23, 45),
                ExecutionStatus.PENDING,
                List.of(firstOrder, secondOrder),
                null,
                null);

        jobRepository.save(job);
        orderBroker.willReturn(1L, BrokerOrderResult.confirmationRequired(null, "timeout"));

        ExecutionJob executed = executor.execute(1L, LocalDateTime.of(2025, 12, 21, 23, 45), ExecutionTriggerType.AUTOMATED);

        assertThat(executed.getOrders().stream()
                .filter(o -> o.getId().equals(1L))
                .findFirst()
                .orElseThrow()
                .getStatus()).isEqualTo(ExecutionOrderStatus.CONFIRMATION_REQUIRED);
        assertThat(executed.getOrders().stream()
                .filter(o -> o.getId().equals(1L))
                .findFirst()
                .orElseThrow()
                .getRequestedMarketAt()).isEqualTo(LocalDateTime.of(2025, 12, 21, 23, 45));
        assertThat(executed.getOrders().stream()
                .filter(o -> o.getId().equals(2L))
                .findFirst()
                .orElseThrow()
                .getStatus()).isEqualTo(ExecutionOrderStatus.SKIPPED);
        assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void 리스크_한도_위반_슬리피지가_나오면_남은_주문을_skip한다() {
        TradingOperationProps strictRiskProps = new TradingOperationProps(
                OperatingMode.AUTO_LIVE,
                new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                new TradingOperationProps.RiskLimitProps(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("0.10")),
                new TradingOperationProps.AlertsProps(false, 30, null));
        riskLimitService = new ExecutionRiskLimitService(strictRiskProps, jobRepository);

        RetryableOrderExecutor retryableExecutor = new RetryableOrderExecutor(
                orderBroker,
                fillChecker,
                canceller,
                new ExecutionOrderFactory(
                        FakeRealtimePriceProvider.withLastPrices(
                                Map.of("QQQ", new BigDecimal("100.00"), "TQQQ", new BigDecimal("100.00"))),
                        new MarketLikePricingPolicy(new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000)),
                new MarketLikePricingPolicy(new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000),
                riskLimitService);
        retryableExecutor.setWaitMs(0);
        executor = new ExecutionJobExecutor(
                jobRepository,
                retryableExecutor,
                orderInquiry,
                guard,
                riskLimitService,
                alertPublisher,
                marketCalendarProps,
                fixedClock);

        ExecutionOrder sellOrder = order(1L, "TQQQ", ExecutionOrderSide.SELL, 5);
        ExecutionOrder buyOrder = order(2L, "QQQ", ExecutionOrderSide.BUY, 10);
        ExecutionJob job = ExecutionJob.rehydrate(
                1L,
                LocalDate.of(2025, 12, 21),
                LocalDateTime.of(2025, 12, 21, 23, 45),
                ExecutionStatus.PENDING,
                List.of(buyOrder, sellOrder),
                null,
                null);

        jobRepository.save(job);
        orderBroker.willReturn(1L, BrokerOrderResult.success("SELL_ORD", "ok"));
        fillChecker.setFullyFilled("SELL_ORD", 5, new BigDecimal("495"));

        ExecutionJob executed = executor.execute(1L, LocalDateTime.of(2025, 12, 21, 23, 45), ExecutionTriggerType.AUTOMATED);

        assertThat(executed.getOrders().stream()
                .filter(o -> o.getId().equals(1L))
                .findFirst()
                .orElseThrow()
                .getStatus()).isEqualTo(ExecutionOrderStatus.ACCEPTED);
        assertThat(executed.getOrders().stream()
                .filter(o -> o.getId().equals(2L))
                .findFirst()
                .orElseThrow()
                .getStatus()).isEqualTo(ExecutionOrderStatus.SKIPPED);
        assertThat(executed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(alertPublisher).publish(argThat(alert -> alert.type() == OpsAlertType.RISK_LIMIT_BREACH));
    }

    private ExecutionJob singleOrderJob(ExecutionOrder order) {
        return ExecutionJob.rehydrate(
                1L,
                LocalDate.of(2025, 12, 21),
                LocalDateTime.of(2025, 12, 21, 23, 45),
                ExecutionStatus.PENDING,
                List.of(order),
                null,
                null);
    }

    private ExecutionOrder order(Long id, String symbol, ExecutionOrderSide side, long qty) {
        return ExecutionOrder.rehydrate(
                id,
                symbol,
                side,
                qty,
                new BigDecimal("100"),
                new BigDecimal("100"),
                ExecutionOrderStatus.PLANNED,
                null,
                null);
    }
}
