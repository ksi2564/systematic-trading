package my.side.trading.core.domain.execution.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionJobTest {

    @Test
    void 모든_주문이_종료되면_완료되고_거절이_있으면_실패로_표시한다() {
        LocalDateTime now = LocalDateTime.of(2025, 12, 21, 23, 45);

        ExecutionOrder o1 = ExecutionOrder.rehydrate(
                1L, "QQQ", ExecutionOrderSide.BUY, 1,
                new BigDecimal("100"), new BigDecimal("100"),
                ExecutionOrderStatus.PLANNED, null, null
        );

        ExecutionOrder o2 = ExecutionOrder.rehydrate(
                2L, "QLD", ExecutionOrderSide.BUY, 1,
                new BigDecimal("100"), new BigDecimal("100"),
                ExecutionOrderStatus.PLANNED, null, null
        );

        ExecutionJob job = ExecutionJob.rehydrate(
                10L,
                LocalDate.of(2025, 12, 21),
                now,
                ExecutionStatus.PENDING,
                List.of(o1, o2),
                null,
                null
        );

        job.start(now);

        job.markOrderRequested(1L, "주문요청");
        job.rejectOrder(1L, null, "fail", now);

        assertThat(job.getCompletedAt()).as("아직 모든 주문이 terminal이 아니므로 완료되면 안 됨").isNull();
        assertThat(job.getStatus()).as("모든 주문이 terminal이 아니면 최종 FAILED 확정하지 않음")
                .isNotEqualTo(ExecutionStatus.FAILED);

        job.markOrderRequested(2L, "주문요청");
        job.acceptOrder(2L, "BID-2", "ok", now);

        assertThat(job.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(job.getCompletedAt()).isEqualTo(now);
    }
}
