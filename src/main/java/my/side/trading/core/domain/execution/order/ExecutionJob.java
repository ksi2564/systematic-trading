package my.side.trading.core.domain.execution.order;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ExecutionJob(
        Long id,
        LocalDate signalDate,           // 이 리밸런싱 신호의 기준 날짜 (EOD)
        LocalDateTime executeAfter,     // 언제부터 실행 가능?
        ExecutionStatus status,         // PENDING / RUNNING / DONE / FAILED
        int strategyVersion,
        List<ExecutionOrder> orders,    // 실제로 실행할 주문 목록
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}
