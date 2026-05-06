package my.side.trading.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.*;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
    private ExecutionOrderSide side;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "ref_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal refPrice;

    @Column(name = "limit_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal limitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ExecutionOrderStatus status;

    @Column(name = "broker_order_id", length = 32)
    private String brokerOrderId;

    @Column(name = "message", length = 128)
    private String message;

    @Column(name = "requested_market_at")
    private LocalDateTime requestedMarketAt;

    public ExecutionOrder toDomain() {
        return ExecutionOrder.rehydrate(
                id,
                symbol,
                side,
                quantity,
                refPrice,
                limitPrice,
                status,
                brokerOrderId,
                message,
                requestedMarketAt
        );
    }

    public static ExecutionOrderEntity from(ExecutionOrder o, ExecutionJobEntity jobEntity) {
        return ExecutionOrderEntity.builder()
                .id(o.getId())
                .job(jobEntity)
                .symbol(o.getSymbol())
                .side(o.getSide())
                .quantity(o.getQuantity())
                .refPrice(o.getRefPrice())
                .limitPrice(o.getLimitPrice())
                .status(o.getStatus())
                .brokerOrderId(o.getBrokerOrderId())
                .message(o.getMessage())
                .requestedMarketAt(o.getRequestedMarketAt())
                .build();
    }
}
