package my.side.trading.core.application.execution;

import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.execution.plan.RebalanceType;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.testutil.FakeExecutionJobRepository;
import my.side.trading.testutil.FakeRealtimePriceProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionJobCreateServiceTest {

    @Test
    void 계획_가능한_주문이_하나라도_있으면_Job_생성() {
        FakeRealtimePriceProvider priceProvider = new FakeRealtimePriceProvider(Map.of(
                "QQQ", new BigDecimal("100.00")
        ));
        MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.25")
        );
        ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

        FakeExecutionJobRepository repo = new FakeExecutionJobRepository();
        ExecutionJobCreateService service = new ExecutionJobCreateService(factory, repo);

        Portfolio portfolio = new Portfolio(new BigDecimal("1000.00"), List.of());

        RebalanceDecision decision = RebalanceDecision.yes(
                RebalanceType.THRESHOLD,
                "test",
                List.of(new my.side.trading.core.domain.execution.plan.OrderIntent(
                        "QQQ",
                        ExecutionOrderSide.BUY,
                        new BigDecimal("500.00"),
                        "buy test"
                ))
        );

        var jobOpt = service.createJob(
                LocalDate.of(2025, 12, 21),
                LocalDateTime.of(2025, 12, 21, 23, 45),
                decision,
                portfolio
        );

        assertThat(jobOpt).isPresent();
        assertThat(jobOpt.get().getOrders()).hasSize(1);
        assertThat(jobOpt.get().getId()).isNotNull();
    }

    @Test
    void 계획_가능한_주문이_없으면_Job_생성_안함() {
        FakeRealtimePriceProvider priceProvider = new FakeRealtimePriceProvider(Map.of(
                "QQQ", new BigDecimal("100.00")
        ));
        MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.25")
        );
        ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

        FakeExecutionJobRepository repo = new FakeExecutionJobRepository();
        ExecutionJobCreateService service = new ExecutionJobCreateService(factory, repo);

        // 현금이 거의 없음 -> BUY 주문은 qty=0
        Portfolio portfolio = new Portfolio(new BigDecimal("10.00"), List.of());

        RebalanceDecision decision = RebalanceDecision.yes(
                RebalanceType.THRESHOLD,
                "test",
                List.of(new OrderIntent(
                        "QQQ",
                        ExecutionOrderSide.BUY,
                        new BigDecimal("500.00"),
                        "buy test"
                ))
        );

        var jobOpt = service.createJob(
                LocalDate.of(2025, 12, 21),
                LocalDateTime.of(2025, 12, 21, 9, 0),
                decision,
                portfolio
        );

        assertThat(jobOpt).isEmpty();
    }

    @Test
    void 같은_signalDate_Job이_이미_있으면_새로_생성_안함() {
        FakeRealtimePriceProvider priceProvider = new FakeRealtimePriceProvider(Map.of(
                "QQQ", new BigDecimal("100.00")
        ));
        MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.25")
        );
        ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

        FakeExecutionJobRepository repo = new FakeExecutionJobRepository();
        ExecutionJobCreateService service = new ExecutionJobCreateService(factory, repo);

        Portfolio portfolio = new Portfolio(new BigDecimal("1000.00"), List.of());

        RebalanceDecision decision = RebalanceDecision.yes(
                RebalanceType.THRESHOLD,
                "test",
                List.of(new OrderIntent(
                        "QQQ",
                        ExecutionOrderSide.BUY,
                        new BigDecimal("500.00"),
                        "buy test"
                ))
        );

        LocalDate signalDate = LocalDate.of(2025, 12, 21);
        LocalDateTime executeAfter = LocalDateTime.of(2025, 12, 21, 23, 45);

        // 1회 생성 -> job 정상 생성
        var first = service.createJob(signalDate, executeAfter, decision, portfolio);
        assertThat(first).isPresent();

        // 2회 생성 시도 -> empty
        var second = service.createJob(signalDate, executeAfter, decision, portfolio);
        assertThat(second).isEmpty();
    }
}
