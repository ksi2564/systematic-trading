package my.side.trading.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.*;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "portfolio_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PortfolioSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "total_value", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalValue;

    @Column(name = "cash", nullable = false, precision = 18, scale = 4)
    private BigDecimal cash;

    @Column(name = "w_base", nullable = false, precision = 7, scale = 4)
    private BigDecimal wBase;

    @Column(name = "w_qld", nullable = false, precision = 7, scale = 4)
    private BigDecimal wQld;

    @Column(name = "w_tqqq", nullable = false, precision = 7, scale = 4)
    private BigDecimal wTqqq;

    // DD 퍼센트 (예: 15.23)
    @Column(name = "dd_percent", nullable = false, precision = 7, scale = 4)
    private BigDecimal ddPercent;

    public PortfolioSnapshot toDomain() {
        return new PortfolioSnapshot(
                asOfDate,
                totalValue,
                cash,
                wBase,
                wQld,
                wTqqq,
                ddPercent
        );
    }

    public static PortfolioSnapshotEntity from(Long id, PortfolioSnapshot snapshot) {
        return PortfolioSnapshotEntity.builder()
                .id(id)
                .asOfDate(snapshot.asOfDate())
                .totalValue(snapshot.totalValue())
                .cash(snapshot.cash())
                .wBase(snapshot.wBase())
                .wQld(snapshot.wQld())
                .wTqqq(snapshot.wTqqq())
                .ddPercent(snapshot.ddPercent())
                .build();
    }
}
