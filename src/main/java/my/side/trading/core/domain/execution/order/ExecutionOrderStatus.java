package my.side.trading.core.domain.execution.order;

/**
 * 실행 주문의 생명주기 동안 가질 수 있는 다양한 상태를 나타내는 Enum
 * 각 주문이 가질 수 있는 특정 상태를 설명
 */
public enum ExecutionOrderStatus {
    PLANNED,    // 주문 수량이 확정되었으나 아직 전송되지 않은 상태
    REQUESTED,  // 주문이 전송된 상태
    ACCEPTED,   // 주문 요청이 수락된 상태
    REJECTED,   // 주문 요청이 거절된 상태
    CANCELED    // 주문이 취소된 상태
}
