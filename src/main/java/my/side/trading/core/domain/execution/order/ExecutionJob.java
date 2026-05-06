package my.side.trading.core.domain.execution.order;

import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Getter
public class ExecutionJob {
    private final Long id;
    private final LocalDate signalDate;         // 이 리밸런싱 신호의 기준 날짜 (EOD)
    private final LocalDateTime executeAfter;   // 언제부터 실행 가능?
    private ExecutionStatus status;
    private final List<ExecutionOrder> orders;  // 실제로 실행할 주문 목록
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private ExecutionJob(
            Long id,
            LocalDate signalDate,
            LocalDateTime executeAfter,
            ExecutionStatus status,
            List<ExecutionOrder> orders,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
        if (signalDate == null) throw new IllegalArgumentException("signalDate는 필수");
        if (executeAfter == null) throw new IllegalArgumentException("execute after는 필수");
        if (orders == null || orders.isEmpty()) throw new IllegalArgumentException("orders는 1개 이상 필요");

        this.id = id;
        this.signalDate = signalDate;
        this.executeAfter = executeAfter;
        this.status = (status == null) ? ExecutionStatus.PENDING : status;
        this.orders = new ArrayList<>(orders); // 외부 변경 차단
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public static ExecutionJob create(LocalDate signalDate, LocalDateTime executeAfter, List<ExecutionOrder> orders) {
        return new ExecutionJob(
                null,
                signalDate,
                executeAfter,
                ExecutionStatus.PENDING,
                orders,
                null,
                null
        );
    }

    public static ExecutionJob rehydrate(
            Long id,
            LocalDate signalDate,
            LocalDateTime executeAfter,
            ExecutionStatus status,
            List<ExecutionOrder> orders,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
        return new ExecutionJob(id, signalDate, executeAfter, status, orders, startedAt, completedAt);
    }

    public List<ExecutionOrder> getOrders() {
        return Collections.unmodifiableList(orders);
    }

    public void start(LocalDateTime now) {
        if (now == null) throw new IllegalArgumentException("now는 필수");
        if (status != ExecutionStatus.PENDING) throw new IllegalStateException("PENDING만 start 가능: " + status);
        if (now.isBefore(executeAfter)) throw new IllegalStateException("executeAfter 이전에는 start 불가");
        this.status = ExecutionStatus.RUNNING;
        this.startedAt = now;
        this.completedAt = null;
    }

    public void markOrderRequested(Long orderId, String message) {
        requireRunning();
        findOrderById(orderId).markRequested(message);
    }

    public void remarkOrderRequested(Long orderId, String message) {
        requireRunning();
        findOrderById(orderId).remarkRequested(message);
    }

    public void markOrderRequested(String symbol, ExecutionOrderSide side, String message) {
        requireRunning();
        findOrderBySymbolSide(symbol, side).markRequested(message);
    }

    public void acceptOrder(Long orderId, String brokerOrderId, String message, LocalDateTime now) {
        requireRunning();
        findOrderById(orderId).accept(brokerOrderId, message);
        completeIfAllTerminal(now);
    }

    public void rejectOrder(Long orderId, String brokerOrderId, String message, LocalDateTime now) {
        requireRunning();
        findOrderById(orderId).reject(brokerOrderId, message);
        completeIfAllTerminal(now);
    }

    public void requireOrderConfirmation(Long orderId, String brokerOrderId, String message, LocalDateTime now) {
        requireRunning();
        findOrderById(orderId).requireConfirmation(brokerOrderId, message);
        completeIfAllTerminal(now);
    }

    public void resolveOrderConfirmation(Long orderId, String brokerOrderId, String message, LocalDateTime now) {
        if (now == null) throw new IllegalArgumentException("now는 필수");
        findOrderById(orderId).resolveConfirmationAsAccepted(brokerOrderId, message);
        recomputeTerminalStatus(now);
    }

    public void keepOrderConfirmationRequired(Long orderId, String message, LocalDateTime now) {
        if (now == null) throw new IllegalArgumentException("now는 필수");
        findOrderById(orderId).keepConfirmationRequired(message);
        recomputeTerminalStatus(now);
    }

    public void cancelOrder(Long orderId, String message, LocalDateTime now) {
        requireRunning();
        findOrderById(orderId).cancel(message);
        completeIfAllTerminal(now);
    }

    public void skipOrder(Long orderId, String message, LocalDateTime now) {
        requireRunning();
        findOrderById(orderId).markSkipped(message);
        completeIfAllTerminal(now);
    }

    public void completeIfAllTerminal(LocalDateTime now) {
        if (now == null) throw new IllegalArgumentException("now는 필수");
        if (status != ExecutionStatus.RUNNING) return;
        recomputeTerminalStatus(now);
    }

    private void recomputeTerminalStatus(LocalDateTime now) {
        boolean allTerminal = orders.stream().allMatch(ExecutionOrder::isTerminal);
        if (!allTerminal) return;

        boolean anyFailed = orders.stream().anyMatch(o ->
                o.getStatus() == ExecutionOrderStatus.REJECTED
                        || o.getStatus() == ExecutionOrderStatus.CONFIRMATION_REQUIRED);
        this.status = anyFailed ? ExecutionStatus.FAILED : ExecutionStatus.COMPLETED;
        this.completedAt = now;
    }

    public void replaceOrder(ExecutionOrder updated) {
        requireRunning();
        if (updated == null) throw new IllegalArgumentException("updated order는 필수");
        if (updated.getId() == null) throw new IllegalArgumentException("updated order id는 필수");

        for (int i = 0; i < orders.size(); i++) {
            ExecutionOrder current = orders.get(i);
            if (Objects.equals(current.getId(), updated.getId())) {
                if (current.isTerminal()) {
                    throw new IllegalStateException("terminal order는 교체 불가: " + current.getStatus());
                }
                orders.set(i, updated);
                return;
            }
        }
        throw new NoSuchElementException("해당 orderId를 찾을 수 없음: " + updated.getId());
    }


    private void requireRunning() {
        if (status != ExecutionStatus.RUNNING) {
            throw new IllegalStateException("RUNNING만 가능: " + status);
        }
    }

    private ExecutionOrder findOrderById(Long orderId) {
        if (orderId == null) throw new IllegalArgumentException("order id는 필수");
        return orders.stream()
                .filter(o -> Objects.equals(o.getId(), orderId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("해당 orderId를 찾을 수 없음: " + orderId));
    }

    private ExecutionOrder findOrderBySymbolSide(String symbol, ExecutionOrderSide side) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol은 필수");
        if (side == null) throw new IllegalArgumentException("side는 필수");
        return orders.stream()
                .filter(o -> symbol.equals(o.getSymbol()) && side == o.getSide())
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("symbol/side 주문을 찾을 수 없음: " + symbol + "/" + side));
    }
}
