package my.side.trading.core.domain.execution.order;

/**
 * 트레이딩 시스템에서 실행 프로세스의 다양한 상태를 나타내는 열거형
 * 주문 세트를 실행하는 과정에서 실행 프로세스의 생명주기를 추적하는 데 사용
 */
public enum ExecutionStatus {
    PENDING,   // 아직 시작되지 않고 대기 중인 상태
    RUNNING,   // 현재 진행 중인 상태
    COMPLETED, // 성공적으로 완료된 상태
    FAILED     // 재처리 필요한 주문이 있는 상태
}
