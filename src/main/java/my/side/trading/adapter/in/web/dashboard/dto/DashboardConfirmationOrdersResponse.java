package my.side.trading.adapter.in.web.dashboard.dto;

import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DashboardConfirmationOrdersResponse(
        DashboardHistoryResponse.DisplayTimeZones displayTimeZones,
        DashboardPageInfo page,
        List<ConfirmationOrderItem> orders
) {
    public record ConfirmationOrderItem(
            JobInfo job,
            DashboardHistoryResponse.OrderHistoryItem order
    ) {
        public static ConfirmationOrderItem from(ExecutionJobRepository.OrderWithJob target) {
            return new ConfirmationOrderItem(
                    JobInfo.from(target.job()),
                    DashboardHistoryResponse.OrderHistoryItem.from(target.order()));
        }
    }

    public record JobInfo(
            Long id,
            LocalDate signalDate,
            Instant executeAfter,
            ExecutionStatus status,
            Instant startedAt,
            Instant completedAt
    ) {
        static JobInfo from(ExecutionJob job) {
            return new JobInfo(
                    job.getId(),
                    job.getSignalDate(),
                    job.getExecuteAfter(),
                    job.getStatus(),
                    job.getStartedAt(),
                    job.getCompletedAt());
        }
    }
}
