package my.side.trading.core.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;
import my.side.trading.core.domain.execution.order.ExecutionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

    // 집행 가능 시각 (다음 거래일 장 시작 후 15~30분 등)
    @Column(name = "execute_after", nullable = false)
    private LocalDateTime executeAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ExecutionStatus status;
}
