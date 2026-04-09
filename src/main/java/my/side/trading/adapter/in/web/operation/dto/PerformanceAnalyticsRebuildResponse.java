package my.side.trading.adapter.in.web.operation.dto;

import my.side.trading.core.application.portfolio.PerformanceAnalyticsBackfillService;

import java.time.LocalDate;

public record PerformanceAnalyticsRebuildResponse(
        LocalDate startDate,
        LocalDate endDate,
        int processedCount,
        int actualReadyCount
) {
    public static PerformanceAnalyticsRebuildResponse from(PerformanceAnalyticsBackfillService.RebuildResult result) {
        return new PerformanceAnalyticsRebuildResponse(
                result.startDate(),
                result.endDate(),
                result.processedCount(),
                result.actualReadyCount()
        );
    }
}
