package my.side.trading.core.application.execution;

import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.execution.plan.RebalanceType;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.testutil.FakeExecutionJobRepository;
import my.side.trading.testutil.FakeRealtimePriceProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExecutionJobCreateServiceTest {

    @Test
    void 계획_가능한_주문이_있으면_job을_생성한다() {
        FakeExecutionJobRepository repo = new FakeExecutionJobRepository();
        ExecutionJobCreateService service = createService(repo, BigDecimal.ZERO, BigDecimal.ZERO);

        Portfolio portfolio = new Portfolio(new BigDecimal("1000.00"), List.of());
        RebalanceDecision decision = decision(new BigDecimal("50"));

        var jobOpt = service.createJob(
                LocalDate.of(2025, 12, 21),
                LocalDateTime.of(2025, 12, 21, 23, 45),
                decision,
                portfolio);

        assertThat(jobOpt).isPresent();
        assertThat(jobOpt.get().getOrders()).hasSize(1);
        assertThat(jobOpt.get().getId()).isNotNull();
    }

    @Test
    void 계획_가능한_주문이_없으면_job을_생성하지_않는다() {
        FakeExecutionJobRepository repo = new FakeExecutionJobRepository();
        ExecutionJobCreateService service = createService(repo, BigDecimal.ZERO, BigDecimal.ZERO);

        Portfolio portfolio = new Portfolio(new BigDecimal("10.00"), List.of(
                new Position("QQQ", new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("100.00"))));
        WeightSet targetWeights = new WeightSet(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO);
        RebalanceDecision decision = RebalanceDecision.yes(
                RebalanceType.THRESHOLD,
                "test",
                targetWeights,
                List.of(new OrderIntent("QQQ", ExecutionOrderSide.BUY, "buy test")));

        var jobOpt = service.createJob(
                LocalDate.of(2025, 12, 21),
                LocalDateTime.of(2025, 12, 21, 9, 0),
                decision,
                portfolio);

        assertThat(jobOpt).isEmpty();
    }

    @Test
    void 같은_signalDate_job이_이미_있으면_새로_생성하지_않는다() {
        FakeExecutionJobRepository repo = new FakeExecutionJobRepository();
        OpsAlertPublisher alertPublisher = mock(OpsAlertPublisher.class);
        ExecutionJobCreateService service = createService(repo, BigDecimal.ZERO, BigDecimal.ZERO, alertPublisher);

        Portfolio portfolio = new Portfolio(new BigDecimal("1000.00"), List.of());
        RebalanceDecision decision = decision(new BigDecimal("50"));

        LocalDate signalDate = LocalDate.of(2025, 12, 21);
        LocalDateTime executeAfter = LocalDateTime.of(2025, 12, 21, 23, 45);

        var first = service.createJob(signalDate, executeAfter, decision, portfolio);
        assertThat(first).isPresent();

        var second = service.createJob(signalDate, executeAfter, decision, portfolio);
        assertThat(second).isEmpty();
        verify(alertPublisher).publish(argThat(alert -> alert.type() == OpsAlertType.DUPLICATE_SIGNAL_JOB_DETECTED));
    }

    @Test
    void 주문_금액_한도를_초과하면_job_생성을_차단한다() {
        FakeExecutionJobRepository repo = new FakeExecutionJobRepository();
        OpsAlertPublisher alertPublisher = mock(OpsAlertPublisher.class);
        ExecutionJobCreateService service = createService(repo, new BigDecimal("300.00"), BigDecimal.ZERO, alertPublisher);

        Portfolio portfolio = new Portfolio(new BigDecimal("1000.00"), List.of());
        RebalanceDecision decision = decision(new BigDecimal("50"));

        assertThatThrownBy(() -> service.createJob(
                LocalDate.of(2025, 12, 21),
                LocalDateTime.of(2025, 12, 21, 23, 45),
                decision,
                portfolio))
                .isInstanceOf(ExecutionRiskLimitExceededException.class)
                .hasMessageContaining("ORDER_NOTIONAL");
        verify(alertPublisher).publish(argThat(alert -> alert.type() == OpsAlertType.RISK_LIMIT_BREACH));
    }

    private ExecutionJobCreateService createService(
            FakeExecutionJobRepository repo,
            BigDecimal maxOrderNotionalUsd,
            BigDecimal maxDailyTurnoverPct) {
        return createService(repo, maxOrderNotionalUsd, maxDailyTurnoverPct, alert -> {});
    }

    private ExecutionJobCreateService createService(
            FakeExecutionJobRepository repo,
            BigDecimal maxOrderNotionalUsd,
            BigDecimal maxDailyTurnoverPct,
            OpsAlertPublisher alertPublisher) {
        FakeRealtimePriceProvider priceProvider = FakeRealtimePriceProvider.withLastPrices(Map.of(
                "QQQ", new BigDecimal("100.00")));
        MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000);
        ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);
        ExecutionRiskLimitService riskLimitService = new ExecutionRiskLimitService(
                new TradingOperationProps(
                        OperatingMode.AUTO_LIVE,
                        new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                        new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                        new TradingOperationProps.RiskLimitProps(
                                maxOrderNotionalUsd,
                                maxDailyTurnoverPct,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO),
                        new TradingOperationProps.AlertsProps(false, 30)),
                repo);
        return new ExecutionJobCreateService(factory, repo, riskLimitService, alertPublisher);
    }

    private RebalanceDecision decision(BigDecimal targetQqqWeight) {
        WeightSet targetWeights = new WeightSet(targetQqqWeight, BigDecimal.ZERO, BigDecimal.ZERO);
        return RebalanceDecision.yes(
                RebalanceType.THRESHOLD,
                "test",
                targetWeights,
                List.of(new OrderIntent("QQQ", ExecutionOrderSide.BUY, "buy test")));
    }
}
