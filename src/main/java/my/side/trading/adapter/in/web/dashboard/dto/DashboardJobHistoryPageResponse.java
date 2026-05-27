package my.side.trading.adapter.in.web.dashboard.dto;

import java.util.List;

public record DashboardJobHistoryPageResponse(
        DashboardHistoryResponse.DisplayTimeZones displayTimeZones,
        DashboardPageInfo page,
        List<DashboardHistoryResponse.JobHistoryItem> jobs
) {
}
