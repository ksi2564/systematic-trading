package my.side.trading.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.*;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "execution_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ExecutionJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 리밸런싱 신호 기준 날짜 (전날 EOD)
    @Column(name = "signal_date", nullable = false)
    private LocalDate signalDate;

    // 집행 가능 시각 (다음 거래일 장 시작 후 15분 뒤)
    @Column(name = "execute_after", nullable = false)
    private Instant executeAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ExecutionStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public ExecutionJob toDomain(List<ExecutionOrder> orders) {
        return ExecutionJob.rehydrate(
                id,
                signalDate,
                executeAfter,
                status,
                List.copyOf(orders),
                startedAt,
                completedAt
        );
    }

    public static ExecutionJobEntity from(ExecutionJob job) {
        return ExecutionJobEntity.builder()
                .id(job.getId())
                .signalDate(job.getSignalDate())
                .executeAfter(job.getExecuteAfter())
                .status(job.getStatus())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .build();
    }
}
