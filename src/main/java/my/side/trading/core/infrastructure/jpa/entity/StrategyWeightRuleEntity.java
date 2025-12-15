package my.side.trading.core.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "strategy_weight_rule")
public class StrategyWeightRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // DD 구간 시작 (퍼센트 값, 예: 15.00)
    @Column(name = "dd_from", nullable = false, precision = 5, scale = 2)
    private BigDecimal ddFrom;

    // DD 구간 끝 (퍼센트 값, 예: 25.00)
    @Column(name = "dd_to", nullable = false, precision = 5, scale = 2)
    private BigDecimal ddTo;

    // 비중표 (퍼센트 값)
    @Column(name = "w_qqq", nullable = false, precision = 5, scale = 2)
    private BigDecimal wQqq;

    @Column(name = "w_qld", nullable = false, precision = 5, scale = 2)
    private BigDecimal wQld;

    @Column(name = "w_tqqq", nullable = false, precision = 5, scale = 2)
    private BigDecimal wTqqq;

    // 전략 버전 관리
    @Column(nullable = false)
    private int version;
}
