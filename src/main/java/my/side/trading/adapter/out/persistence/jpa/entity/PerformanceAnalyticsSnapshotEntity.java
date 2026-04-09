package my.side.trading.adapter.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "performance_analytics_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PerformanceAnalyticsSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "nav_usd", nullable = false, precision = 18, scale = 4)
    private BigDecimal navUsd;

    @Column(name = "nav_krw", nullable = false, precision = 18, scale = 4)
    private BigDecimal navKrw;

    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal fxRate;

    @Column(name = "realized_pnl_usd", nullable = false, precision = 18, scale = 4)
    private BigDecimal realizedPnlUsd;

    @Column(name = "realized_pnl_krw", nullable = false, precision = 18, scale = 4)
    private BigDecimal realizedPnlKrw;

    @Column(name = "broker_fee_usd", nullable = false, precision = 18, scale = 4)
    private BigDecimal brokerFeeUsd;

    @Column(name = "broker_fee_krw", nullable = false, precision = 18, scale = 4)
    private BigDecimal brokerFeeKrw;

    @Column(name = "tax_usd", nullable = false, precision = 18, scale = 4)
    private BigDecimal taxUsd;

    @Column(name = "tax_krw", nullable = false, precision = 18, scale = 4)
    private BigDecimal taxKrw;

    @Column(name = "actual_data_ready", nullable = false)
    private boolean actualDataReady;

    @Column(name = "holding_cost_estimate_usd", nullable = false, precision = 18, scale = 4)
    private BigDecimal holdingCostEstimateUsd;

    @Column(name = "holding_cost_estimate_krw", nullable = false, precision = 18, scale = 4)
    private BigDecimal holdingCostEstimateKrw;

    @Column(name = "holding_cost_configured", nullable = false)
    private boolean holdingCostConfigured;

    public PerformanceAnalyticsSnapshot toDomain() {
        return new PerformanceAnalyticsSnapshot(
                asOfDate,
                navUsd,
                navKrw,
                fxRate,
                realizedPnlUsd,
                realizedPnlKrw,
                brokerFeeUsd,
                brokerFeeKrw,
                taxUsd,
                taxKrw,
                actualDataReady,
                holdingCostEstimateUsd,
                holdingCostEstimateKrw,
                holdingCostConfigured
        );
    }

    public static PerformanceAnalyticsSnapshotEntity from(Long id, PerformanceAnalyticsSnapshot snapshot) {
        return PerformanceAnalyticsSnapshotEntity.builder()
                .id(id)
                .asOfDate(snapshot.asOfDate())
                .navUsd(snapshot.navUsd())
                .navKrw(snapshot.navKrw())
                .fxRate(snapshot.fxRate())
                .realizedPnlUsd(snapshot.realizedPnlUsd())
                .realizedPnlKrw(snapshot.realizedPnlKrw())
                .brokerFeeUsd(snapshot.brokerFeeUsd())
                .brokerFeeKrw(snapshot.brokerFeeKrw())
                .taxUsd(snapshot.taxUsd())
                .taxKrw(snapshot.taxKrw())
                .actualDataReady(snapshot.actualDataReady())
                .holdingCostEstimateUsd(snapshot.holdingCostEstimateUsd())
                .holdingCostEstimateKrw(snapshot.holdingCostEstimateKrw())
                .holdingCostConfigured(snapshot.holdingCostConfigured())
                .build();
    }
}
