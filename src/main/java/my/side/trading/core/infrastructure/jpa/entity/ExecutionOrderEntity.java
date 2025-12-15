package my.side.trading.core.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;
import my.side.trading.core.domain.execution.plan.OrderSide;

@Entity
@Table(name = "execution_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ExecutionOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private ExecutionJobEntity job;

    @Column(name = "symbol", length = 16, nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 4, nullable = false)
    private OrderSide side;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "reason", length = 32, nullable = false)
    private String reason;
}
