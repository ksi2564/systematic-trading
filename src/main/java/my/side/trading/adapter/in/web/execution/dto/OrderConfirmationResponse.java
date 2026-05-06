package my.side.trading.adapter.in.web.execution.dto;

import my.side.trading.core.application.execution.ExecutionOrderConfirmationResult;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;

public record OrderConfirmationResponse(
        Long jobId,
        Long orderId,
        ExecutionOrderStatus previousStatus,
        ExecutionOrderStatus currentStatus,
        String brokerOrderId,
        OrderInquiryResult.Status inquiryStatus,
        String message
) {
    public static OrderConfirmationResponse from(ExecutionOrderConfirmationResult result) {
        return new OrderConfirmationResponse(
                result.jobId(),
                result.orderId(),
                result.previousStatus(),
                result.currentStatus(),
                result.brokerOrderId(),
                result.inquiryStatus(),
                result.message());
    }
}
