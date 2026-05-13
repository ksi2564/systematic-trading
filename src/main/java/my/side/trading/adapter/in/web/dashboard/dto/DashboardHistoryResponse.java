package my.side.trading.adapter.in.web.dashboard.dto;

import lombok.Builder;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.ExecutionStatus;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import my.side.trading.core.domain.operation.OperatingModeTransitionType;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Builder
public record DashboardHistoryResponse(
        int limit,
        DisplayTimeZones displayTimeZones,
        List<JobHistoryItem> jobs,
        List<OperatingModeAuditItem> operatingModeAudits,
        List<PerformanceSnapshotItem> performanceSnapshots,
        List<PerformanceAnalyticsSnapshotItem> performanceAnalyticsSnapshots
) {
    @Builder
    public record DisplayTimeZones(
            String operator,
            String market
    ) {
    }

    @Builder
    public record JobHistoryItem(
            Long id,
            LocalDate signalDate,
            Instant executeAfter,
            ExecutionStatus status,
            Instant startedAt,
            Instant completedAt,
            int orderCount,
            int acceptedOrderCount,
            int rejectedOrderCount,
            int canceledOrderCount,
            int skippedOrderCount,
            List<OrderHistoryItem> orders
    ) {
        public static JobHistoryItem from(ExecutionJob job) {
            List<OrderHistoryItem> orders = job.getOrders().stream()
                    .map(OrderHistoryItem::from)
                    .toList();
            return new JobHistoryItem(
                    job.getId(),
                    job.getSignalDate(),
                    job.getExecuteAfter(),
                    job.getStatus(),
                    job.getStartedAt(),
                    job.getCompletedAt(),
                    orders.size(),
                    countByStatus(job.getOrders(), ExecutionOrderStatus.ACCEPTED),
                    countByStatus(job.getOrders(), ExecutionOrderStatus.REJECTED),
                    countByStatus(job.getOrders(), ExecutionOrderStatus.CANCELED),
                    countByStatus(job.getOrders(), ExecutionOrderStatus.SKIPPED),
                    orders
            );
        }

        private static int countByStatus(List<ExecutionOrder> orders, ExecutionOrderStatus status) {
            return (int) orders.stream()
                    .filter(order -> order.getStatus() == status)
                    .count();
        }
    }

    @Builder
    public record OrderHistoryItem(
            Long id,
            String symbol,
            String side,
            long quantity,
            BigDecimal refPrice,
            BigDecimal limitPrice,
            String status,
            String brokerOrderId,
            String message
    ) {
        public static OrderHistoryItem from(ExecutionOrder order) {
            return new OrderHistoryItem(
                    order.getId(),
                    order.getSymbol(),
                    order.getSide().name(),
                    order.getQuantity(),
                    order.getRefPrice(),
                    order.getLimitPrice(),
                    order.getStatus().name(),
                    order.getBrokerOrderId(),
                    order.getMessage()
            );
        }
    }

    @Builder
    public record OperatingModeAuditItem(
            Long id,
            OperatingMode previousMode,
            OperatingMode targetMode,
            OperatingModeTransitionType transitionType,
            OperatingModeTriggerSource triggerSource,
            String triggerCode,
            String requestedBy,
            String reason,
            String approvedBy,
            Instant approvedAt,
            Instant createdAt
    ) {
        public static OperatingModeAuditItem from(OperatingModeAuditEvent audit) {
            return new OperatingModeAuditItem(
                    audit.id(),
                    audit.previousMode(),
                    audit.targetMode(),
                    audit.transitionType(),
                    audit.triggerSource(),
                    audit.triggerCode(),
                    audit.requestedBy(),
                    audit.reason(),
                    audit.approvedBy(),
                    audit.approvedAt(),
                    audit.createdAt()
            );
        }
    }

    @Builder
    public record PerformanceSnapshotItem(
            LocalDate asOfDate,
            BigDecimal totalValue,
            BigDecimal cash,
            BigDecimal wBase,
            BigDecimal wQqq,
            BigDecimal wQld,
            BigDecimal wTqqq,
            BigDecimal ddPercent
    ) {
        public static PerformanceSnapshotItem from(PortfolioSnapshot snapshot) {
            return new PerformanceSnapshotItem(
                    snapshot.asOfDate(),
                    snapshot.totalValue(),
                    snapshot.cash(),
                    snapshot.wBase(),
                    snapshot.wQqq(),
                    snapshot.wQld(),
                    snapshot.wTqqq(),
                    snapshot.ddPercent()
            );
        }
    }

    @Builder
    public record PerformanceAnalyticsSnapshotItem(
            LocalDate asOfDate,
            BigDecimal navUsd,
            BigDecimal navKrw,
            BigDecimal fxRate,
            BigDecimal realizedPnlUsd,
            BigDecimal realizedPnlKrw,
            BigDecimal brokerFeeUsd,
            BigDecimal brokerFeeKrw,
            BigDecimal taxUsd,
            BigDecimal taxKrw,
            boolean actualDataReady,
            BigDecimal holdingCostEstimateUsd,
            BigDecimal holdingCostEstimateKrw,
            boolean holdingCostConfigured
    ) {
        public static PerformanceAnalyticsSnapshotItem from(PerformanceAnalyticsSnapshot snapshot) {
            return new PerformanceAnalyticsSnapshotItem(
                    snapshot.asOfDate(),
                    snapshot.navUsd(),
                    snapshot.navKrw(),
                    snapshot.fxRate(),
                    snapshot.realizedPnlUsd(),
                    snapshot.realizedPnlKrw(),
                    snapshot.brokerFeeUsd(),
                    snapshot.brokerFeeKrw(),
                    snapshot.taxUsd(),
                    snapshot.taxKrw(),
                    snapshot.actualDataReady(),
                    snapshot.holdingCostEstimateUsd(),
                    snapshot.holdingCostEstimateKrw(),
                    snapshot.holdingCostConfigured()
            );
        }
    }
}
