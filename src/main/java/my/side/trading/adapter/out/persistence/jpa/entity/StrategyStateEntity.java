package my.side.trading.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.*;
import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "strategy_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class StrategyStateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "ath", nullable = false, precision = 18, scale = 4)
    private BigDecimal ath;

    @Column(name = "last_close", nullable = false, precision = 18, scale = 4)
    private BigDecimal lastClose;

    // DD 퍼센트 값 (예: 15.23 == 15.23%)
    @Column(name = "drawdown_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal drawdownPct;

    // 현재 ATH 이후 최악의 DD
    @Column(name = "max_drawdown_pct_since_ath", nullable = false, precision = 7, scale = 4)
    private BigDecimal maxDrawdownPctSinceAth;

    @Enumerated(EnumType.STRING)
    @Column(name = "dd_bucket", length = 32, nullable = false)
    private DdBucket ddBucket;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", length = 16, nullable = false)
    private StrategyPhase phase;

    @Column(name = "w_qqq", nullable = false, precision = 5, scale = 2)
    private BigDecimal wQqq;

    @Column(name = "w_qld", nullable = false, precision = 5, scale = 2)
    private BigDecimal wQld;

    @Column(name = "w_tqqq", nullable = false, precision = 5, scale = 2)
    private BigDecimal wTqqq;

    @Column(name = "strategy_on", nullable = false)
    private boolean strategyOn;

    @Column(name = "version", nullable = false)
    private int version;

    public StrategyState toDomain() {
        return new StrategyState(
                asOfDate,
                ath,
                lastClose,
                drawdownPct,
                maxDrawdownPctSinceAth,
                ddBucket,
                phase,
                new WeightSet(wQqq, wQld, wTqqq),
                strategyOn,
                version
        );
    }

    public static StrategyStateEntity from(StrategyState s) {
        return StrategyStateEntity.builder()
                .asOfDate(s.asOfDate())
                .ath(s.ath())
                .lastClose(s.lastClose())
                .drawdownPct(s.drawdownPct())
                .maxDrawdownPctSinceAth(s.maxDrawdownPctSinceAth())
                .ddBucket(s.ddBucket())
                .phase(s.phase())
                .wQqq(s.targetWeights().wQqq())
                .wQld(s.targetWeights().wQld())
                .wTqqq(s.targetWeights().wTqqq())
                .strategyOn(s.strategyOn())
                .version(s.version())
                .build();
    }
}

