package my.side.trading.core.domain.execution.order;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class ExecutionOrder {
    private final Long id;
    private final String symbol;
    private final ExecutionOrderSide side;
    private final long quantity;            // 주식 수
    private final BigDecimal refPrice;      // 주문 요청 시점 기준 가격(RealtimePrice)
    private final BigDecimal limitPrice;    // 실제 주문 가격 (refPrice에 buffer 가격을 둬서 채결 확률을 높임)

    private ExecutionOrderStatus status;
    private String brokerOrderId;           // KIS 주문번호(주문번호, 주문시각)
    private String message;                 // 응답 메세지

    private ExecutionOrder(
            Long id,
            String symbol,
            ExecutionOrderSide side,
            long quantity,
            BigDecimal refPrice,
            BigDecimal limitPrice,
            ExecutionOrderStatus status,
            String brokerOrderId,
            String message
    ) {
        if (quantity <= 0) throw new IllegalArgumentException("주식 수량은 1개 이상이어야 주문 가능");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol은 필수");
        if (side == null) throw new IllegalArgumentException("order side는 필수");
        if (refPrice == null || refPrice.signum() <= 0) throw new IllegalArgumentException("ref price는 0보다 커야 함");
        if (limitPrice == null || limitPrice.signum() <= 0) throw new IllegalArgumentException("limit price는 0보다 커야 함");

        this.id = id;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.refPrice = refPrice;
        this.limitPrice = limitPrice;
        this.status = (status == null) ? ExecutionOrderStatus.PLANNED : status;
        this.brokerOrderId = brokerOrderId;
        this.message = message;
    }

    public static ExecutionOrder create(
            String symbol,
            ExecutionOrderSide side,
            long quantity,
            BigDecimal refPrice,
            BigDecimal limitPrice
    ) {
        return new ExecutionOrder(
                null,
                symbol,
                side,
                quantity,
                refPrice,
                limitPrice,
                ExecutionOrderStatus.PLANNED,
                null,
                null
        );
    }

    public static ExecutionOrder rehydrate(
            Long id,
            String symbol,
            ExecutionOrderSide side,
            long quantity,
            BigDecimal refPrice,
            BigDecimal limitPrice,
            ExecutionOrderStatus status,
            String brokerOrderId,
            String message
    ) {
        return new ExecutionOrder(
                id,
                symbol,
                side,
                quantity,
                refPrice,
                limitPrice,
                status,
                brokerOrderId,
                message
        );
    }

    public void markRequested(String message) {
        requireStatus(ExecutionOrderStatus.PLANNED);
        this.message = message;
        this.status = ExecutionOrderStatus.REQUESTED;
    }

    public void remarkRequested(String message) {
        requireStatus(ExecutionOrderStatus.REQUESTED);
        this.message = message;
    }

    public void markSkipped(String message) {
        if (isTerminal()) throw new IllegalStateException("터미널 상태는 SKIPPED 전이 불가: " + status);
        this.message = message;
        this.status = ExecutionOrderStatus.SKIPPED;
    }

    public void accept(String brokerOrderId, String message) {
        requireStatus(ExecutionOrderStatus.REQUESTED);
        requireBrokerOrderId(brokerOrderId);
        this.brokerOrderId = brokerOrderId;
        this.message = message;
        this.status = ExecutionOrderStatus.ACCEPTED;
    }

    public void reject(String brokerOrderId, String message) {
        requireStatus(ExecutionOrderStatus.REQUESTED);
        this.brokerOrderId = (brokerOrderId == null || brokerOrderId.isBlank()) ? null : brokerOrderId;
        this.message = message;
        this.status = ExecutionOrderStatus.REJECTED;
    }

    public void requireConfirmation(String brokerOrderId, String message) {
        requireStatus(ExecutionOrderStatus.REQUESTED);
        this.brokerOrderId = (brokerOrderId == null || brokerOrderId.isBlank()) ? null : brokerOrderId;
        this.message = message;
        this.status = ExecutionOrderStatus.CONFIRMATION_REQUIRED;
    }

    public void resolveConfirmationAsAccepted(String brokerOrderId, String message) {
        requireStatus(ExecutionOrderStatus.CONFIRMATION_REQUIRED);
        requireBrokerOrderId(brokerOrderId);
        this.brokerOrderId = brokerOrderId;
        this.message = message;
        this.status = ExecutionOrderStatus.ACCEPTED;
    }

    public void keepConfirmationRequired(String message) {
        requireStatus(ExecutionOrderStatus.CONFIRMATION_REQUIRED);
        this.message = message;
    }

    public void cancel(String message) {
        if (isTerminal()) throw new IllegalStateException("터미널 상태는 취소 불가: " + status);
        this.message = message;
        this.status = ExecutionOrderStatus.CANCELED;
    }

    public boolean isTerminal() {
        return status == ExecutionOrderStatus.ACCEPTED
                || status == ExecutionOrderStatus.CONFIRMATION_REQUIRED
                || status == ExecutionOrderStatus.REJECTED
                || status == ExecutionOrderStatus.CANCELED
                || status == ExecutionOrderStatus.SKIPPED;
    }

    public ExecutionOrder changeQty(long newQuantity) {
        return rehydrate(id, symbol, side, newQuantity, refPrice, limitPrice, status, brokerOrderId, message);
    }

    private void requireStatus(ExecutionOrderStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException("상태 전이 불가. expected=" + expected + ", actual=" + status);
        }
    }

    private void requireBrokerOrderId(String brokerOrderId) {
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            throw new IllegalArgumentException("brokerOrderId는 필수");
        }
    }
}
