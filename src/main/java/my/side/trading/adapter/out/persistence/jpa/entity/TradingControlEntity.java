package my.side.trading.adapter.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "trading_control")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class TradingControlEntity {

    @Id
    @Column(name = "control_key", length = 64, nullable = false)
    private String key;

    @Column(name = "control_value", length = 64, nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
