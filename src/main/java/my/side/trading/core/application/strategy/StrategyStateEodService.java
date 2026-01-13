package my.side.trading.core.application.strategy;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.strategy.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class StrategyStateEodService {

    private static final int CURRENT_STRATEGY_VERSION = 1;

    private final StrategyStateRepository strategyStateRepository;

    /**
     * EOD 기준으로 새로운 StrategyState를 계산하고 저장한다.
     * 초기 StrategyState는 QQQ 의 전고점 데이터를 직접 DB에 Insert (1회성)
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
        BigDecimal ath, dd, maxDd;

        // 전고 돌파 시
        if (qqqClose.compareTo(prevAth) > 0) {
            // ATH 갱신 + DD 리셋
            ath = qqqClose;
            dd = BigDecimal.ZERO;
            maxDd = BigDecimal.ZERO;
        } else {
            ath = prevAth;
            dd = calculateDrawdownPercent(ath, qqqClose);
            maxDd = prev.maxDrawdownPctSinceAth().max(dd);
        }

        // bucket + phase + 목표 비중 결정
        DdBucket bucket = DdBucket.from(dd);
        StrategyPhase phase = StrategyPhase.from(maxDd, dd);
        WeightSet targetWeights = decideTargetWeights(phase, dd, prev);
        // strategyOn은 KillSwitch이므로 이전 상태 유지
        boolean strategyOn = prev.strategyOn();

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

    private WeightSet decideTargetWeights(StrategyPhase phase, BigDecimal dd, StrategyState prev) {
        return switch (phase) {
            case NORMAL -> WeightSet.normal();
            case RECOVERY -> WeightSet.recovery();
            case DRAWDOWN -> dd.compareTo(BigDecimal.TEN) > 0 && dd.compareTo(BigDecimal.valueOf(15)) < 0 ?
                    prev.targetWeights() : WeightSet.drawDown(dd); // 10% < DD < 15% 이면 -> 이전 비중 유지
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
