package my.side.trading.core.application.operation;

import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.ExecutionStatus;
import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.testutil.FakeExecutionJobRepository;
import my.side.trading.testutil.FakeStrategyStateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationsKpiServiceTest {

    @Test
    void 최신_EOD가_없고_중복_job과_미정리주문이_있으면_kpi_breach로_판단한다() {
        LocalDate marketDate = LocalDate.of(2026, 4, 2);
        FakeStrategyStateRepository stateRepository = new FakeStrategyStateRepository(state(LocalDate.of(2026, 4, 1)));
        FakeExecutionJobRepository jobRepository = new FakeExecutionJobRepository();
        jobRepository.save(job(1L, marketDate, ExecutionStatus.FAILED, order(1L, ExecutionOrderStatus.REJECTED)));
        jobRepository.save(job(2L, marketDate, ExecutionStatus.PENDING, order(2L, ExecutionOrderStatus.REQUESTED)));
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(marketCalendarService.getMarketStatus(marketDate)).thenReturn(my.side.trading.core.domain.time.MarketStatus.REGULAR);

        OperationsKpiService service = new OperationsKpiService(
                stateRepository,
                jobRepository,
                marketCalendarService,
                new TradingOperationProps(
                        my.side.trading.core.domain.operation.OperatingMode.AUTO_LIVE,
                        new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                        new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                        new TradingOperationProps.RiskLimitProps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                        new TradingOperationProps.AlertsProps(false, 30, null)));

        OperationsKpiSnapshot snapshot = service.snapshot();

        assertThat(snapshot.latestEodSuccess()).isFalse();
        assertThat(snapshot.duplicateSignalJobCount()).isEqualTo(1);
        assertThat(snapshot.unresolvedOrderCount()).isEqualTo(2);
        assertThat(snapshot.orderFailureRatePct()).isEqualByComparingTo("100.00");
        assertThat(snapshot.breached()).isTrue();
        assertThat(snapshot.breaches()).contains(
                OperationsKpiBreach.LATEST_EOD_MISSING,
                OperationsKpiBreach.DUPLICATE_SIGNAL_JOB_DETECTED,
                OperationsKpiBreach.UNRESOLVED_ORDERS_PRESENT,
                OperationsKpiBreach.ORDER_FAILURE_RATE_EXCEEDED);
    }

    @Test
    void 휴장일에는_최신_EOD가_없어도_EOD_breach로_보지_않는다() {
        LocalDate marketDate = LocalDate.of(2026, 7, 4);
        FakeStrategyStateRepository stateRepository = new FakeStrategyStateRepository(state(LocalDate.of(2026, 7, 3)));
        FakeExecutionJobRepository jobRepository = new FakeExecutionJobRepository();
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(marketCalendarService.getMarketStatus(marketDate)).thenReturn(my.side.trading.core.domain.time.MarketStatus.HOLIDAY);

        OperationsKpiService service = new OperationsKpiService(
                stateRepository,
                jobRepository,
                marketCalendarService,
                new TradingOperationProps(
                        my.side.trading.core.domain.operation.OperatingMode.AUTO_LIVE,
                        new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                        new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                        new TradingOperationProps.RiskLimitProps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                        new TradingOperationProps.AlertsProps(false, 30, null)));

        OperationsKpiSnapshot snapshot = service.snapshot();

        assertThat(snapshot.latestEodSuccess()).isTrue();
        assertThat(snapshot.breached()).isFalse();
    }

    private StrategyState state(LocalDate asOfDate) {
        return new StrategyState(
                asOfDate,
                new BigDecimal("500"),
                new BigDecimal("450"),
                new BigDecimal("10"),
                new BigDecimal("15"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.normal(),
                true,
                1);
    }

    private ExecutionJob job(Long id, LocalDate signalDate, ExecutionStatus status, ExecutionOrder order) {
        return ExecutionJob.rehydrate(
                id,
                signalDate,
                LocalDateTime.of(signalDate, java.time.LocalTime.of(23, 45)),
                status,
                List.of(order),
                null,
                null);
    }

    private ExecutionOrder order(Long id, ExecutionOrderStatus status) {
        return ExecutionOrder.rehydrate(
                id,
                "QQQ",
                ExecutionOrderSide.BUY,
                1,
                new BigDecimal("100"),
                new BigDecimal("100"),
                status,
                null,
                null);
    }
}
