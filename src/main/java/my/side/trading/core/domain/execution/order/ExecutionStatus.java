package my.side.trading.core.domain.execution.order;

public enum ExecutionStatus {
    PENDING,    // 아직 실행 전 (실행 대기)
    RUNNING,    // 실행 중
    COMPLETED,  // 실행 완료(모든 주문이 성공/실패 상태)
    FAILED      // 실행 실패 (시스템적 오류로 재처리 필요 상태)
}
