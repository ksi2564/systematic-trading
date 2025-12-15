package my.side.trading.core.application.strategy;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.jpa.repository.StrategyStateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class StrategyStateEodService {

    private static final int CURRENT_STRATEGY_VERSION = 1;

    private final StrategyStateRepository strategyStateRepository;
    private final WeightRuleService weightRuleService;

    /**
     * EOD 기준으로 새로운 StrategyState를 계산하고 저장한다.
     *
     * @param asOfDate 상태 기준 일자 (예: 2025-12-10, "장 마감일")
     * @param qqqClose 해당 날 QQQ 종가
     * @return 계산된 StrategyState 도메인 객체
     */
    public StrategyState runEod(LocalDate asOfDate, BigDecimal qqqClose) {
        StrategyState prev = strategyStateRepository.findLatestState()
                .orElseThrow(() -> new IllegalStateException("초기 StrategyState가 DB에 없습니다."));

        StrategyState newState = calculateNextState(asOfDate, qqqClose, prev);

        return strategyStateRepository.save(newState);
    }


    private StrategyState calculateNextState(LocalDate asOfDate, BigDecimal qqqClose, StrategyState prev) {

        BigDecimal prevAth = prev.ath();
        BigDecimal ath;

        // 전고 돌파 시
        if (qqqClose.compareTo(prevAth) > 0) {
            // ATH 갱신 + DD 리셋 + NORMAL + QQQ 100%
            ath = qqqClose;
            BigDecimal dd = BigDecimal.ZERO;
            BigDecimal maxDd = BigDecimal.ZERO;

            return new StrategyState(
                    asOfDate,
                    ath,
                    qqqClose,
                    dd,
                    maxDd,
                    DdBucket.ZERO_TO_15,
                    StrategyPhase.NORMAL,
                    new WeightSet(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO),
                    false,
                    CURRENT_STRATEGY_VERSION
            );
        }

        ath = prevAth;
        BigDecimal dd = calculateDrawdownPercent(ath, qqqClose);
        BigDecimal maxDd = prev.maxDrawdownPctSinceAth().max(dd);

        DdBucket bucket = DdBucket.from(dd);

        // phase + 목표 비중 결정
        StrategyPhase phase = StrategyPhase.from(maxDd, dd);
        WeightSet targetWeights = decideTargetWeights(phase, dd, prev, CURRENT_STRATEGY_VERSION);

        // 전략 ON/OFF
        boolean strategyOn = (phase != StrategyPhase.NORMAL);

        return new StrategyState(
                asOfDate,
                ath,
                qqqClose,
                dd,
                maxDd,
                bucket,
                phase,
                targetWeights,
                strategyOn,
                CURRENT_STRATEGY_VERSION
        );
    }

    private WeightSet decideTargetWeights(StrategyPhase phase, BigDecimal dd, StrategyState prev, int version) {

        return switch (phase) {
            case NORMAL -> WeightSet.normal();
            case RECOVERY -> WeightSet.recovery();
            case DRAWDOWN -> {
                // 10% < DD < 15% 이고, 직전에도 DRAWDOWN이면 -> 이전 비중 유지
                boolean inMidRecoveryBand =
                        dd.compareTo(BigDecimal.TEN) > 0 && dd.compareTo(BigDecimal.valueOf(15)) < 0;
                yield inMidRecoveryBand && prev.phase() == StrategyPhase.DRAWDOWN ?
                        prev.targetWeights() : weightRuleService.getTargetWeights(dd, version);
            }
        };
    }

    private BigDecimal calculateDrawdownPercent(BigDecimal ath, BigDecimal close) {
        if (ath.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = ath.subtract(close);
        // (ATH - Close) / ATH * 100, 소수점 4자리까지
        return diff
                .divide(ath, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
