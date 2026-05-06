package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.OrderInquiry;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class ExecutionOrderConfirmationService {

    private static final int MAX_ORDER_MESSAGE_LENGTH = 128;

    private final ExecutionJobRepository jobRepository;
    private final OrderInquiry orderInquiry;

    public ExecutionOrderConfirmationResult confirm(Long jobId, Long orderId, LocalDateTime now) {
        if (now == null) throw new IllegalArgumentException("now는 필수");

        ExecutionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        ExecutionOrder order = findOrder(job, orderId);
        ExecutionOrderStatus previousStatus = order.getStatus();
        if (previousStatus != ExecutionOrderStatus.CONFIRMATION_REQUIRED) {
            throw new IllegalStateException("CONFIRMATION_REQUIRED 주문만 확인 가능: " + previousStatus);
        }

        OrderInquiryResult inquiryResult = orderInquiry.confirm(order);
        String message = confirmationMessage(inquiryResult);
        if (inquiryResult.found()) {
            job.resolveOrderConfirmation(orderId, inquiryResult.brokerOrderId(), message, now);
        } else {
            job.keepOrderConfirmationRequired(orderId, message, now);
        }

        ExecutionJob saved = jobRepository.save(job);
        ExecutionOrder savedOrder = findOrder(saved, orderId);
        return new ExecutionOrderConfirmationResult(
                saved.getId(),
                savedOrder.getId(),
                previousStatus,
                savedOrder.getStatus(),
                savedOrder.getBrokerOrderId(),
                inquiryResult.status(),
                savedOrder.getMessage());
    }

    private ExecutionOrder findOrder(ExecutionJob job, Long orderId) {
        if (orderId == null) throw new IllegalArgumentException("order id는 필수");
        return job.getOrders().stream()
                .filter(order -> orderId.equals(order.getId()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("해당 orderId를 찾을 수 없음: " + orderId));
    }

    private String confirmationMessage(OrderInquiryResult result) {
        String detail = result.message() == null || result.message().isBlank() ? "-" : result.message();
        String message = switch (result.status()) {
            case FOUND -> "주문 확인 완료 | " + detail;
            case NOT_FOUND -> "주문 확인 유지: 조회 결과 없음 | " + detail;
            case INQUIRY_FAILED -> "주문 확인 유지: 조회 실패 | " + detail;
        };
        return message.length() <= MAX_ORDER_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ORDER_MESSAGE_LENGTH);
    }
}
