package my.side.trading.adapter.in.web.operation;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.in.web.operation.dto.PerformanceAnalyticsRebuildRequest;
import my.side.trading.adapter.in.web.operation.dto.PerformanceAnalyticsRebuildResponse;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.portfolio.PerformanceAnalyticsBackfillService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/operations/performance-analytics")
public class PerformanceAnalyticsController {

    private final PerformanceAnalyticsBackfillService performanceAnalyticsBackfillService;
    private final MarketCalendarService marketCalendarService;

    @PostMapping("/rebuild")
    public ApiResponse<PerformanceAnalyticsRebuildResponse> rebuild(
            @RequestBody(required = false) PerformanceAnalyticsRebuildRequest request
    ) {
        LocalDate endDate = request != null && request.endDate() != null
                ? request.endDate()
                : marketCalendarService.currentMarketDate();
        var result = request != null && request.startDate() != null
                ? performanceAnalyticsBackfillService.rebuild(request.startDate(), endDate)
                : performanceAnalyticsBackfillService.rebuildDefaultRange(endDate);
        return ApiResponse.success(PerformanceAnalyticsRebuildResponse.from(result));
    }
}
