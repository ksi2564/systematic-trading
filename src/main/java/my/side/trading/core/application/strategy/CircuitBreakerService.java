package my.side.trading.core.application.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.config.TradingCircuitBreakerProps;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 서킷 브레이커 로직(안전장치)
 * - VIX 필터: VIX >= 35 시 레버리지 확대 중단
 * - 200MA 필터: QQQ < 200MA 시 TQQQ 비중 한 단계 낮게 유지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CircuitBreakerService {

    private final TradingCircuitBreakerProps props;

    /**
     * VIX가 임계값 이상인지 확인
     */
    public boolean isVixTriggered(BigDecimal vix) {
        if (!props.isEnabled() || !props.isVixEnabled() || vix == null) {
            return false;
        }
        boolean triggered = vix.compareTo(props.getVixThreshold()) >= 0;
        if (triggered) {
            log.warn("VIX 서킷 브레이커가 발동했습니다: VIX={} >= threshold={}", vix, props.getVixThreshold());
        }
        return triggered;
    }

    /**
     * QQQ가 200MA 미만인지 확인
     */
    public boolean isMaTriggered(BigDecimal qqqClose, BigDecimal ma) {
        if (!props.isEnabled() || qqqClose == null || ma == null) {
            return false;
        }
        boolean triggered = qqqClose.compareTo(ma) < 0;
        if (triggered) {
            log.warn("200MA 서킷 브레이커가 발동했습니다: QQQ={} < MA{}={}", qqqClose, props.getMaPeriod(), ma);
        }
        return triggered;
    }

    /**
     * 서킷 브레이커를 적용해 비중을 조정한다.
     *
     * @param original     원래 목표 비중
     * @param prevWeights  이전 비중 (VIX 트리거 시 TQQQ 확대 방지용)
     * @param vixTriggered VIX >= 35 여부
     * @param maTriggered  QQQ < 200MA 여부
     * @return 조정된 비중
     */
    public WeightSet adjustWeights(WeightSet original, WeightSet prevWeights, boolean vixTriggered,
            boolean maTriggered) {
        if (!props.isEnabled()) {
            return original;
        }

        WeightSet adjusted = original;

        // 200MA 필터: TQQQ를 한 단계 낮은 구간으로 유지
        if (maTriggered) {
            adjusted = reduceOneBucket(adjusted);
            log.info("200MA 필터를 적용했습니다: adjusted={}", adjusted);
        }

        // VIX 필터: TQQQ 비중이 이전보다 높아지지 않도록 제한
        if (vixTriggered) {
            if (prevWeights == null) {
                log.warn("VIX 필터가 발동했지만 이전 비중이 없어 현재 조정 비중을 유지합니다: adjusted={}", adjusted);
                return adjusted;
            }
            if (adjusted.wTqqq().compareTo(prevWeights.wTqqq()) > 0) {
                // TQQQ 비중이 늘어나려 하면 → 이전 비중으로 유지
                adjusted = prevWeights;
                log.info("VIX 필터를 적용해 이전 비중을 유지합니다: adjusted={}", adjusted);
            }
        }

        return adjusted;
    }

    /**
     * 한 단계 보수적인 비중으로 조정 (TQQQ 비중 감소)
     */
    private WeightSet reduceOneBucket(WeightSet weights) {
        // TQQQ 비중에 따라 한 단계 낮은 버킷으로 이동
        int tqqqWeight = weights.wTqqq().intValue();

        if (tqqqWeight >= 60) {
            // MORE_THAN_45 (20/20/60) → FROM_35_TO_45 (30/30/40)
            return WeightSet.of(30, 30, 40);
        } else if (tqqqWeight >= 40) {
            // FROM_35_TO_45 (30/30/40) → FROM_25_TO_35 (40/40/20)
            return WeightSet.of(40, 40, 20);
        } else if (tqqqWeight >= 20) {
            // FROM_25_TO_35 (40/40/20) → FROM_15_TO_25 (60/30/10)
            return WeightSet.of(60, 30, 10);
        } else if (tqqqWeight >= 10) {
            // FROM_15_TO_25 (60/30/10) → LESS_THAN_15 (100/0/0)
            return WeightSet.of(100, 0, 0);
        }

        // 이미 최소 수준이면 그대로
        return weights;
    }
}
