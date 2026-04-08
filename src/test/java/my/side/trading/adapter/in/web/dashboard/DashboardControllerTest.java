package my.side.trading.adapter.in.web.dashboard;

import my.side.trading.adapter.in.scheduler.StrategyEodScheduler;
import my.side.trading.adapter.in.web.dashboard.dto.DashboardHistoryResponse;
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
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshotRepository;
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
        PortfolioSnapshotRepository portfolioSnapshotRepository = mock(PortfolioSnapshotRepository.class);
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
                portfolioSnapshotRepository,
                operatingModeService);

        DashboardResponse response = controller.getSummary().data();

        assertThat(response.operatingMode()).isEqualTo(OperatingMode.MANUAL_LIVE);
        assertThat(response.performance().dataAvailable()).isTrue();
        assertThat(response.performance().latestNav()).isEqualByComparingTo("1000.0000");
        assertThat(response.performance().recentMonthlyPnl()).hasSize(1);
        assertThat(response.recentOperatingModeAudits()).hasSize(1);
        assertThat(response.recentOperatingModeAudits().getFirst().triggerCode()).isEqualTo("KPI_BREACH");
    }

    @Test
    void historyContainsJobAuditAndPerformanceSnapshotsLatestFirst() {
        PortfolioService portfolioService = mock(PortfolioService.class);
        StrategyStateRepository strategyStateRepository = mock(StrategyStateRepository.class);
        StrategyEodScheduler scheduler = mock(StrategyEodScheduler.class);
        ExecutionJobRepository jobRepository = mock(ExecutionJobRepository.class);
        ExecutionGuard executionGuard = mock(ExecutionGuard.class);
        my.side.trading.core.application.operation.OperationsKpiService operationsKpiService =
                mock(my.side.trading.core.application.operation.OperationsKpiService.class);
        PortfolioPerformanceService portfolioPerformanceService = mock(PortfolioPerformanceService.class);
        PortfolioSnapshotRepository portfolioSnapshotRepository = mock(PortfolioSnapshotRepository.class);
        OperatingModeService operatingModeService = mock(OperatingModeService.class);

        when(jobRepository.findAll()).thenReturn(List.of(
                sampleJob(1L, LocalDate.of(2026, 4, 2), LocalDateTime.of(2026, 4, 2, 23, 40)),
                sampleJob(2L, LocalDate.of(2026, 4, 3), LocalDateTime.of(2026, 4, 3, 23, 45))
        ));
        when(operatingModeService.recentHistory(2)).thenReturn(List.of(
                auditEvent(2L, Instant.parse("2026-04-03T01:00:00Z")),
                auditEvent(1L, Instant.parse("2026-04-02T01:00:00Z"))
        ));
        when(portfolioSnapshotRepository.findAllOrderByAsOfDateAsc()).thenReturn(List.of(
                new PortfolioSnapshot(
                        LocalDate.of(2026, 4, 2),
                        new BigDecimal("980.0000"),
                        new BigDecimal("100.0000"),
                        new BigDecimal("100.0000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("2.0000")),
                new PortfolioSnapshot(
                        LocalDate.of(2026, 4, 3),
                        new BigDecimal("1000.0000"),
                        new BigDecimal("120.0000"),
                        new BigDecimal("90.0000"),
                        new BigDecimal("10.0000"),
                        BigDecimal.ZERO,
                        new BigDecimal("1.0000"))
        ));

        DashboardController controller = new DashboardController(
                portfolioService,
                strategyStateRepository,
                scheduler,
                jobRepository,
                executionGuard,
                operationsKpiService,
                portfolioPerformanceService,
                portfolioSnapshotRepository,
                operatingModeService);

        DashboardHistoryResponse response = controller.getHistory(2).data();

        assertThat(response.limit()).isEqualTo(2);
        assertThat(response.jobs()).hasSize(2);
        assertThat(response.jobs().getFirst().id()).isEqualTo(2L);
        assertThat(response.jobs().getFirst().acceptedOrderCount()).isEqualTo(1);
        assertThat(response.jobs().getFirst().orders().getFirst().symbol()).isEqualTo("QQQ");
        assertThat(response.operatingModeAudits()).hasSize(2);
        assertThat(response.operatingModeAudits().getFirst().id()).isEqualTo(2L);
        assertThat(response.performanceSnapshots()).hasSize(2);
        assertThat(response.performanceSnapshots().getFirst().asOfDate()).isEqualTo(LocalDate.of(2026, 4, 3));
        assertThat(response.performanceSnapshots().getFirst().totalValue()).isEqualByComparingTo("1000.0000");
    }

    @Test
    void historyNormalizesNonPositiveAndExcessiveLimit() {
        PortfolioService portfolioService = mock(PortfolioService.class);
        StrategyStateRepository strategyStateRepository = mock(StrategyStateRepository.class);
        StrategyEodScheduler scheduler = mock(StrategyEodScheduler.class);
        ExecutionJobRepository jobRepository = mock(ExecutionJobRepository.class);
        ExecutionGuard executionGuard = mock(ExecutionGuard.class);
        my.side.trading.core.application.operation.OperationsKpiService operationsKpiService =
                mock(my.side.trading.core.application.operation.OperationsKpiService.class);
        PortfolioPerformanceService portfolioPerformanceService = mock(PortfolioPerformanceService.class);
        PortfolioSnapshotRepository portfolioSnapshotRepository = mock(PortfolioSnapshotRepository.class);
        OperatingModeService operatingModeService = mock(OperatingModeService.class);

        when(jobRepository.findAll()).thenReturn(List.of(sampleJob()));
        when(portfolioSnapshotRepository.findAllOrderByAsOfDateAsc()).thenReturn(List.of());
        when(operatingModeService.recentHistory(20)).thenReturn(List.of());
        when(operatingModeService.recentHistory(100)).thenReturn(List.of());

        DashboardController controller = new DashboardController(
                portfolioService,
                strategyStateRepository,
                scheduler,
                jobRepository,
                executionGuard,
                operationsKpiService,
                portfolioPerformanceService,
                portfolioSnapshotRepository,
                operatingModeService);

        DashboardHistoryResponse defaulted = controller.getHistory(0).data();
        DashboardHistoryResponse capped = controller.getHistory(999).data();

        assertThat(defaulted.limit()).isEqualTo(20);
        assertThat(capped.limit()).isEqualTo(100);
    }

    private OperatingModeAuditEvent auditEvent() {
        return auditEvent(1L, Instant.parse("2026-04-03T00:00:00Z"));
    }

    private OperatingModeAuditEvent auditEvent(Long id, Instant createdAt) {
        return new OperatingModeAuditEvent(
                id,
                OperatingMode.AUTO_LIVE,
                OperatingMode.MANUAL_LIVE,
                OperatingModeTransitionType.DEMOTION,
                OperatingModeTriggerSource.SYSTEM,
                "KPI_BREACH",
                "system",
                "Automatic demotion triggered by KPI_BREACH",
                null,
                null,
                createdAt);
    }

    private ExecutionJob sampleJob() {
        return sampleJob(1L, LocalDate.of(2026, 4, 3), LocalDateTime.of(2026, 4, 3, 23, 45));
    }

    private ExecutionJob sampleJob(Long id, LocalDate signalDate, LocalDateTime executeAfter) {
        return ExecutionJob.rehydrate(
                id,
                signalDate,
                executeAfter,
                ExecutionStatus.COMPLETED,
                List.of(ExecutionOrder.rehydrate(
                        1L,
                        "QQQ",
                        ExecutionOrderSide.BUY,
                        1,
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        ExecutionOrderStatus.ACCEPTED,
                        "ORD-001",
                        "accepted")),
                executeAfter.plusMinutes(1),
                executeAfter.plusMinutes(3));
    }
}
