package my.side.trading.adapter.in.web.dashboard;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.in.scheduler.StrategyEodScheduler;
import my.side.trading.adapter.in.web.dashboard.dto.DashboardResponse;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
import my.side.trading.core.application.portfolio.PortfolioService;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final PortfolioService portfolioService;
    private final StrategyStateRepository strategyStateRepository;
    private final StrategyEodScheduler scheduler; // VIX, 200MA 조회용
    private final ExecutionJobRepository jobRepository;

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

        return ApiResponse.success(DashboardResponse.of(portfolio, state, vix, qqq200Ma, recentJobs));
    }

    @GetMapping("/history")
    public ApiResponse<List<ExecutionJob>> getHistory() {
        // 전체 매매 이력 조회 (최신순)
        var history = jobRepository.findAll().stream()
                .sorted(Comparator.comparing(ExecutionJob::getSignalDate).reversed())
                .toList();

        return ApiResponse.success(history);
    }
}
