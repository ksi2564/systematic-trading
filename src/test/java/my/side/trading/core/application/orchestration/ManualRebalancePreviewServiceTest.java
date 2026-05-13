package my.side.trading.core.application.orchestration;

import my.side.trading.core.application.execution.ExecutionBlockReason;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.execution.ExecutionOrderFactory;
import my.side.trading.core.application.execution.ExecutionRiskLimitService;
import my.side.trading.core.application.execution.RebalanceDecisionService;
import my.side.trading.core.application.execution.RebalanceOrderPlanner;
import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.application.port.out.MarketDataProvider;
import my.side.trading.core.application.portfolio.PortfolioService;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.execution.plan.RebalanceType;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.core.infrastructure.config.TradingStrategyProps;
import my.side.trading.testutil.FakeExecutionJobRepository;
import my.side.trading.testutil.FakeRealtimePriceProvider;
import my.side.trading.testutil.FakeStrategyStateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManualRebalancePreviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-07T12:34:56Z");
    private static final LocalDate SIGNAL_DATE = LocalDate.of(2026, 5, 6);

    @Test
    void 리밸런싱_필요시_주문_후보와_PASS_리스크_결과를_반환한다() {
        FakeExecutionJobRepository jobRepository = new FakeExecutionJobRepository();
        ManualRebalancePreviewService service = createService(jobRepository, BigDecimal.ZERO, decision(true),
                ExecutionBlockReason.PAPER_MODE_BLOCKS_LIVE_EXECUTION, OperatingMode.PAPER);

        ManualRebalancePreview preview = service.preview();

        assertThat(preview.generatedAt()).isEqualTo(NOW);
        assertThat(preview.operatingMode()).isEqualTo(OperatingMode.PAPER);
        assertThat(preview.manualBlockReason()).isEqualTo(ExecutionBlockReason.PAPER_MODE_BLOCKS_LIVE_EXECUTION);
        assertThat(preview.signalDate()).isEqualTo(SIGNAL_DATE);
        assertThat(preview.baseSymbol()).isEqualTo("QQQM");
        assertThat(preview.signalSymbol()).isEqualTo("QQQM");
        assertThat(preview.vix()).isEqualByComparingTo("18.50");
        assertThat(preview.signal200Ma()).isEqualByComparingTo("440.00");
        assertThat(preview.duplicateSignalJobExists()).isFalse();
        assertThat(preview.orders()).hasSize(1);
        assertThat(preview.orders().getFirst().order().getSymbol()).isEqualTo("QQQM");
        assertThat(preview.orders().getFirst().riskViolation()).isNull();
        assertThat(preview.totalOrderNotional()).isEqualByComparingTo("500.00");
        assertThat(preview.executable()).isFalse();
    }

    @Test
    void 주문_금액_한도_초과시_저장없이_BLOCKED_리스크_결과를_반환한다() {
        FakeExecutionJobRepository jobRepository = new FakeExecutionJobRepository();
        ManualRebalancePreviewService service = createService(jobRepository, new BigDecimal("300.00"), decision(true),
                null, OperatingMode.MANUAL_LIVE);

        ManualRebalancePreview preview = service.preview();

        assertThat(preview.orders()).hasSize(1);
        assertThat(preview.orders().getFirst().riskViolation()).isNotNull();
        assertThat(preview.orders().getFirst().riskViolation().type().name()).isEqualTo("ORDER_NOTIONAL");
        assertThat(preview.executable()).isFalse();
        assertThat(jobRepository.findAll()).isEmpty();
    }

    @Test
    void 같은_signalDate_job이_있으면_중복_상태와_실행불가를_반환한다() {
        FakeExecutionJobRepository jobRepository = new FakeExecutionJobRepository();
        jobRepository.save(ExecutionJob.create(
                SIGNAL_DATE,
                NOW,
                List.of(ExecutionOrder.create("QQQM", ExecutionOrderSide.BUY, 1,
                        new BigDecimal("100.00"), new BigDecimal("100.00")))));
        ManualRebalancePreviewService service = createService(jobRepository, BigDecimal.ZERO, decision(true),
                null, OperatingMode.MANUAL_LIVE);

        ManualRebalancePreview preview = service.preview();

        assertThat(preview.duplicateSignalJobExists()).isTrue();
        assertThat(preview.orders()).hasSize(1);
        assertThat(preview.executable()).isFalse();
    }

    @Test
    void 수동_실행_차단_사유가_있으면_응답에_포함한다() {
        ManualRebalancePreviewService service = createService(new FakeExecutionJobRepository(), BigDecimal.ZERO, decision(true),
                ExecutionBlockReason.KILL_SWITCH_ON, OperatingMode.MANUAL_LIVE);

        ManualRebalancePreview preview = service.preview();

        assertThat(preview.manualBlockReason()).isEqualTo(ExecutionBlockReason.KILL_SWITCH_ON);
        assertThat(preview.executable()).isFalse();
    }

    @Test
    void 실시간_호가_캐시가_없으면_500_대신_실행차단_미리보기를_반환한다() {
        ManualRebalancePreviewService service = createService(
                new FakeExecutionJobRepository(),
                BigDecimal.ZERO,
                decision(true),
                null,
                OperatingMode.MANUAL_LIVE,
                FakeRealtimePriceProvider.withLastPrices(Map.of()));

        ManualRebalancePreview preview = service.preview();

        assertThat(preview.manualBlockReason()).isEqualTo(ExecutionBlockReason.MARKET_QUOTE_UNAVAILABLE);
        assertThat(preview.orders()).isEmpty();
        assertThat(preview.totalOrderNotional()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(preview.executable()).isFalse();
    }

    @Test
    void 리밸런싱이_필요없으면_주문없이_판단_사유만_반환한다() {
        ManualRebalancePreviewService service = createService(new FakeExecutionJobRepository(), BigDecimal.ZERO,
                RebalanceDecision.no("비중 오차가 허용범위 이내"), null, OperatingMode.MANUAL_LIVE);

        ManualRebalancePreview preview = service.preview();

        assertThat(preview.decision().shouldRebalance()).isFalse();
        assertThat(preview.decision().reason()).isEqualTo("비중 오차가 허용범위 이내");
        assertThat(preview.orders()).isEmpty();
        assertThat(preview.totalOrderNotional()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(preview.executable()).isFalse();
    }

    private ManualRebalancePreviewService createService(
            FakeExecutionJobRepository jobRepository,
            BigDecimal maxOrderNotionalUsd,
            RebalanceDecision decision,
            ExecutionBlockReason manualBlockReason,
            OperatingMode operatingMode
    ) {
        return createService(
                jobRepository,
                maxOrderNotionalUsd,
                decision,
                manualBlockReason,
                operatingMode,
                FakeRealtimePriceProvider.withLastPrices(Map.of(
                        "QQQM", new BigDecimal("100.00"))));
    }

    private ManualRebalancePreviewService createService(
            FakeExecutionJobRepository jobRepository,
            BigDecimal maxOrderNotionalUsd,
            RebalanceDecision decision,
            ExecutionBlockReason manualBlockReason,
            OperatingMode operatingMode,
            FakeRealtimePriceProvider priceProvider
    ) {
        StrategyState state = state();
        FakeStrategyStateRepository stateRepository = new FakeStrategyStateRepository(state);
        Portfolio portfolio = new Portfolio(new BigDecimal("1000.00"), List.of());
        PortfolioService portfolioService = mock(PortfolioService.class);
        when(portfolioService.getCurrentPortfolio()).thenReturn(portfolio);

        RebalanceDecisionService decisionService = mock(RebalanceDecisionService.class);
        when(decisionService.decide(eq(state), eq(portfolio), any(), eq(new BigDecimal("18.50")), eq(new BigDecimal("440.00"))))
                .thenReturn(decision);

        MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
        when(marketDataProvider.getVixPrice()).thenReturn(Optional.of(new BigDecimal("18.50")));
        when(marketDataProvider.getSignal200Ma("QQQM")).thenReturn(Optional.of(new BigDecimal("440.00")));

        ExecutionGuard executionGuard = mock(ExecutionGuard.class);
        when(executionGuard.getExecutionBlockReason(ExecutionTriggerType.MANUAL))
                .thenReturn(Optional.ofNullable(manualBlockReason));
        when(executionGuard.currentMode()).thenReturn(operatingMode);

        MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                new BigDecimal("0.01"), 0, 0, 1, 1, BigDecimal.ZERO, 3, 2000);
        ExecutionOrderFactory orderFactory = new ExecutionOrderFactory(priceProvider, pricing);
        ExecutionRiskLimitService riskLimitService = new ExecutionRiskLimitService(
                new TradingOperationProps(
                        OperatingMode.AUTO_LIVE,
                        new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                        new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                        new TradingOperationProps.RiskLimitProps(
                                maxOrderNotionalUsd,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO),
                        new TradingOperationProps.AlertsProps(false, 30, null)),
                jobRepository);
        RebalanceOrderPlanner orderPlanner = new RebalanceOrderPlanner(orderFactory, riskLimitService);

        return new ManualRebalancePreviewService(
                stateRepository,
                portfolioService,
                decisionService,
                marketDataProvider,
                jobRepository,
                executionGuard,
                orderPlanner,
                Clock.fixed(NOW, ZoneOffset.UTC),
                strategyProps());
    }

    private RebalanceDecision decision(boolean shouldRebalance) {
        if (!shouldRebalance) {
            return RebalanceDecision.no("test no");
        }
        return RebalanceDecision.yes(
                RebalanceType.THRESHOLD,
                "test",
                WeightSet.of(50, 0, 0),
                List.of(new OrderIntent("QQQM", ExecutionOrderSide.BUY, "buy test")));
    }

    private StrategyState state() {
        return new StrategyState(
                SIGNAL_DATE,
                "QQQM",
                new BigDecimal("500.00"),
                new BigDecimal("450.00"),
                new BigDecimal("10.00"),
                new BigDecimal("20.00"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.DRAWDOWN,
                WeightSet.of(50, 0, 0),
                true,
                1);
    }

    private TradingStrategyProps strategyProps() {
        return new TradingStrategyProps(null, "QQQM", null, null, null);
    }
}
