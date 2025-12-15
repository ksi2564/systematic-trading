package my.side.trading.core.domain.execution.order;

public enum ExecutionOrderStatus {
    PLANNED,            // 수량 확정 완료(아직 전송 전)
    SENT,               // 주문 전송 완료
    PARTIALLY_FILLED,   // 부분 체결
    FILLED,             // 전량 체결
    REJECTED,           // 증권사 거절(파라미터/규정 등)
    FAILED,             // 시스템/네트워크 등 실패
    CANCELED            // 취소됨
}
