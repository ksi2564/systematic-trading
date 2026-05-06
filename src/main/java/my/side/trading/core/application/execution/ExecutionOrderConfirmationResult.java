package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;

public record ExecutionOrderConfirmationResult(
        Long jobId,
        Long orderId,
        ExecutionOrderStatus previousStatus,
        ExecutionOrderStatus currentStatus,
        String brokerOrderId,
        OrderInquiryResult.Status inquiryStatus,
        String message
) {
}
