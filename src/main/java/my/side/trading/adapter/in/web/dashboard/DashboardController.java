package my.side.trading.adapter.in.web.dashboard;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.in.scheduler.StrategyEodScheduler;
import my.side.trading.adapter.in.web.dashboard.dto.DashboardHistoryResponse;
import my.side.trading.adapter.in.web.dashboard.dto.DashboardPerformanceResponse;
import my.side.trading.adapter.in.web.dashboard.dto.DashboardResponse;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.operation.OperatingModeService;
import my.side.trading.core.application.operation.OperationsKpiService;
import my.side.trading.core.application.port.out.CurrentFxRateProvider;
import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsService;
import my.side.trading.core.application.portfolio.PortfolioService;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshotRepository;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import my.side.trading.core.infrastructure.config.TradingMarketCalendarProps;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Comparator;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final PortfolioService portfolioService;
    private final StrategyStateRepository strategyStateRepository;
    private final StrategyEodScheduler scheduler; // VIX, 200MA 조회용
    private final ExecutionJobRepository jobRepository;
    private final ExecutionGuard executionGuard;
    private final OperationsKpiService operationsKpiService;
    private final PortfolioPerformanceAnalyticsService portfolioPerformanceAnalyticsService;
    private final CurrentFxRateProvider currentFxRateProvider;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final OperatingModeService operatingModeService;
    private final TradingMarketCalendarProps marketCalendarProps;

    private static final String OPERATOR_TIME_ZONE = "Asia/Seoul";
    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT = 100;

    @GetMapping("/summary")
    public ApiResponse<DashboardResponse> getSummary() {
        // 1. 포트폴리오 조회
        var portfolio = portfolioService.getCurrentPortfolio();

        // 2. 전략 상태 조회
        var state = strategyStateRepository.findLatestState()
                .orElse(null);

        // 3. 서킷 브레이커 정보 조회 (VIX, 200MA)
        BigDecimal vix = scheduler.getVix();
        BigDecimal qqq200Ma = scheduler.getQqq200Ma();

        // 4. 최근 실행 작업 조회 (최신 5건)
        var recentJobs = jobRepository.findAll().stream()
                .sorted(Comparator.comparing(ExecutionJob::getSignalDate).reversed())
                .limit(5)
                .toList();

        var operationsKpi = operationsKpiService.snapshot();
        var performance = portfolioPerformanceAnalyticsService.getSummary();
        var realtimePortfolioValuation = DashboardResponse.RealtimePortfolioValuationInfo.from(
                portfolio,
                currentFxRateProvider.getCurrentUsdKrwRate().orElse(null)
        );

        return ApiResponse.success(DashboardResponse.of(
                portfolio,
                state,
                vix,
                qqq200Ma,
                operationsKpi,
                performance,
                realtimePortfolioValuation,
                operatingModeService.currentMode(),
                executionGuard.snapshot(),
                recentJobs,
                operatingModeService.recentHistory(5)));
    }

    @GetMapping("/history")
    public ApiResponse<DashboardHistoryResponse> getHistory(
            @RequestParam(defaultValue = "20") int limit
    ) {
        int normalizedLimit = normalizeLimit(limit);

        var jobs = jobRepository.findAll().stream()
                .sorted(jobComparator())
                .limit(normalizedLimit)
                .map(DashboardHistoryResponse.JobHistoryItem::from)
                .toList();

        var operatingAudits = operatingModeService.recentHistory(normalizedLimit).stream()
                .map(DashboardHistoryResponse.OperatingModeAuditItem::from)
                .toList();

        var performanceSnapshots = portfolioSnapshotRepository.findAllOrderByAsOfDateAsc().stream()
                .sorted(performanceSnapshotComparator())
                .limit(normalizedLimit)
                .map(DashboardHistoryResponse.PerformanceSnapshotItem::from)
                .toList();
        var performanceAnalyticsSnapshots = portfolioPerformanceAnalyticsService.getRecentSnapshots(normalizedLimit).stream()
                .sorted(performanceAnalyticsSnapshotComparator())
                .map(DashboardHistoryResponse.PerformanceAnalyticsSnapshotItem::from)
                .toList();

        return ApiResponse.success(new DashboardHistoryResponse(
                normalizedLimit,
                new DashboardHistoryResponse.DisplayTimeZones(
                        OPERATOR_TIME_ZONE,
                        marketCalendarProps.marketZoneId()),
                jobs,
                operatingAudits,
                performanceSnapshots,
                performanceAnalyticsSnapshots
        ));
    }

    @GetMapping("/performance")
    public ApiResponse<DashboardPerformanceResponse> getPerformance(
            @RequestParam(defaultValue = "60") int dailyLimit
    ) {
        return ApiResponse.success(DashboardPerformanceResponse.from(
                portfolioPerformanceAnalyticsService.getReport(dailyLimit)
        ));
    }

    private int normalizeLimit(int limit) {
        return limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
    }

    private Comparator<ExecutionJob> jobComparator() {
        return Comparator.comparing(ExecutionJob::getSignalDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ExecutionJob::getExecuteAfter, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ExecutionJob::getId, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private Comparator<PortfolioSnapshot> performanceSnapshotComparator() {
        return Comparator.comparing(PortfolioSnapshot::asOfDate, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private Comparator<PerformanceAnalyticsSnapshot> performanceAnalyticsSnapshotComparator() {
        return Comparator.comparing(PerformanceAnalyticsSnapshot::asOfDate, Comparator.nullsLast(Comparator.reverseOrder()));
    }
}
