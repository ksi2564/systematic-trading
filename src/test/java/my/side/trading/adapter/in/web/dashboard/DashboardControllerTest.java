package my.side.trading.adapter.in.web.dashboard;

import my.side.trading.adapter.in.scheduler.StrategyEodScheduler;
import my.side.trading.adapter.in.web.dashboard.dto.DashboardResponse;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.execution.ExecutionGuardSnapshot;
import my.side.trading.core.application.operation.OperatingModeService;
import my.side.trading.core.application.operation.OperationsKpiSnapshot;
import my.side.trading.core.application.portfolio.PortfolioPerformanceService;
import my.side.trading.core.application.portfolio.PortfolioPerformanceSummary;
import my.side.trading.core.application.portfolio.PortfolioService;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.ExecutionStatus;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import my.side.trading.core.domain.operation.OperatingModeTransitionType;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import my.side.trading.core.domain.time.MarketStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardControllerTest {

    @Test
    void summaryContainsOperatingModeAndRecentAuditHistory() {
        PortfolioService portfolioService = mock(PortfolioService.class);
        StrategyStateRepository strategyStateRepository = mock(StrategyStateRepository.class);
        StrategyEodScheduler scheduler = mock(StrategyEodScheduler.class);
        ExecutionJobRepository jobRepository = mock(ExecutionJobRepository.class);
        ExecutionGuard executionGuard = mock(ExecutionGuard.class);
        my.side.trading.core.application.operation.OperationsKpiService operationsKpiService =
                mock(my.side.trading.core.application.operation.OperationsKpiService.class);
        PortfolioPerformanceService portfolioPerformanceService = mock(PortfolioPerformanceService.class);
        OperatingModeService operatingModeService = mock(OperatingModeService.class);

        when(portfolioService.getCurrentPortfolio()).thenReturn(new Portfolio(BigDecimal.TEN, List.of()));
        when(strategyStateRepository.findLatestState()).thenReturn(Optional.empty());
        when(scheduler.getVix()).thenReturn(new BigDecimal("20.5"));
        when(scheduler.getQqq200Ma()).thenReturn(new BigDecimal("500.0"));
        when(jobRepository.findAll()).thenReturn(List.of(sampleJob()));
        when(executionGuard.snapshot()).thenReturn(new ExecutionGuardSnapshot(
                OperatingMode.MANUAL_LIVE,
                true,
                false,
                null,
                null,
                new ExecutionGuardSnapshot.AutoLiveGateSnapshot(5, true, true, true, false)));
        when(operationsKpiService.snapshot()).thenReturn(new OperationsKpiSnapshot(
                LocalDate.of(2026, 4, 3),
                MarketStatus.REGULAR,
                LocalDate.of(2026, 4, 2),
                true,
                0,
                0,
                0,
                0,
                BigDecimal.ZERO,
                false,
                List.of()));
        when(portfolioPerformanceService.getSummary()).thenReturn(new PortfolioPerformanceSummary(
                true,
                null,
                LocalDate.of(2026, 4, 3),
                new BigDecimal("1000.0000"),
                new BigDecimal("1200.0000"),
                new BigDecimal("16.6667"),
                new BigDecimal("25.0000"),
                new BigDecimal("50.0000"),
                new BigDecimal("5.0000"),
                List.of(new PortfolioPerformanceSummary.MonthlyPnl(
                        "2026-04",
                        new BigDecimal("950.0000"),
                        new BigDecimal("1000.0000"),
                        new BigDecimal("50.0000"),
                        new BigDecimal("5.2632")))));
        when(operatingModeService.currentMode()).thenReturn(OperatingMode.MANUAL_LIVE);
        when(operatingModeService.recentHistory(5)).thenReturn(List.of(auditEvent()));

        DashboardController controller = new DashboardController(
                portfolioService,
                strategyStateRepository,
                scheduler,
                jobRepository,
                executionGuard,
                operationsKpiService,
                portfolioPerformanceService,
                operatingModeService);

        DashboardResponse response = controller.getSummary().data();

        assertThat(response.operatingMode()).isEqualTo(OperatingMode.MANUAL_LIVE);
        assertThat(response.performance().dataAvailable()).isTrue();
        assertThat(response.performance().latestNav()).isEqualByComparingTo("1000.0000");
        assertThat(response.performance().recentMonthlyPnl()).hasSize(1);
        assertThat(response.recentOperatingModeAudits()).hasSize(1);
        assertThat(response.recentOperatingModeAudits().getFirst().triggerCode()).isEqualTo("KPI_BREACH");
    }

    private OperatingModeAuditEvent auditEvent() {
        return new OperatingModeAuditEvent(
                1L,
                OperatingMode.AUTO_LIVE,
                OperatingMode.MANUAL_LIVE,
                OperatingModeTransitionType.DEMOTION,
                OperatingModeTriggerSource.SYSTEM,
                "KPI_BREACH",
                "system",
                "Automatic demotion triggered by KPI_BREACH",
                null,
                null,
                Instant.parse("2026-04-03T00:00:00Z"));
    }

    private ExecutionJob sampleJob() {
        return ExecutionJob.rehydrate(
                1L,
                LocalDate.of(2026, 4, 3),
                LocalDateTime.of(2026, 4, 3, 23, 45),
                ExecutionStatus.PENDING,
                List.of(ExecutionOrder.rehydrate(
                        1L,
                        "QQQ",
                        ExecutionOrderSide.BUY,
                        1,
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        ExecutionOrderStatus.PLANNED,
                        null,
                        null)),
                null,
                null);
    }
}
