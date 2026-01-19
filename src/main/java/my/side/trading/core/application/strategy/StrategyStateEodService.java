package my.side.trading.core.application.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.strategy.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyStateEodService {

    private static final int CURRENT_STRATEGY_VERSION = 1;
    private static final int ATH_LOOKUP_DAYS = 365; // 1년치 데이터로 ATH 계산

    private final StrategyStateRepository strategyStateRepository;
    private final QqqHistoricalDataProvider qqqHistoricalDataProvider;

    /**
     * EOD 기준으로 새로운 StrategyState를 계산하고 저장한다.
     * 초기 StrategyState가 없으면 과거 데이터로부터 자동 계산하여 생성한다.
     *
     * @param asOfDate 상태 기준 일자 (예: 2025-12-10, "장 마감일")
     * @param qqqClose 해당 날 QQQ 종가
     * @return 계산된 StrategyState 도메인 객체
     */
    public StrategyState runEod(LocalDate asOfDate, BigDecimal qqqClose) {
        StrategyState prev = strategyStateRepository.findLatestState()
                .orElseGet(() -> initializeState(asOfDate, qqqClose));

        StrategyState newState = calculateNextState(asOfDate, qqqClose, prev);
        return strategyStateRepository.save(newState);
    }

    /**
     * 초기 StrategyState를 과거 QQQ 데이터로부터 계산하여 생성한다.
     * ATH는 과거 1년 종가 중 최대값으로 계산한다.
     */
    private StrategyState initializeState(LocalDate asOfDate, BigDecimal qqqClose) {
        log.info("Initializing StrategyState from historical data...");

        List<BigDecimal> historicalPrices = qqqHistoricalDataProvider.getHistoricalClosePrices(ATH_LOOKUP_DAYS);

        if (historicalPrices.isEmpty()) {
            log.warn("No historical data available, using current close as ATH");
            return createInitialState(asOfDate, qqqClose, qqqClose);
        }

        BigDecimal ath = historicalPrices.stream()
                .max(BigDecimal::compareTo)
                .orElse(qqqClose);

        // ATH가 현재가보다 작으면 현재가가 새 ATH
        if (qqqClose.compareTo(ath) > 0) {
            ath = qqqClose;
        }

        log.info("Calculated ATH from {} historical prices: {}", historicalPrices.size(), ath);
        return createInitialState(asOfDate, qqqClose, ath);
    }

    private StrategyState createInitialState(LocalDate asOfDate, BigDecimal qqqClose, BigDecimal ath) {
        BigDecimal dd = calculateDrawdownPercent(ath, qqqClose);
        BigDecimal maxDd = dd; // 초기 상태에서는 현재 DD가 최대 DD

        DdBucket bucket = DdBucket.from(dd);
        StrategyPhase phase = StrategyPhase.from(maxDd, dd);
        WeightSet targetWeights = decideInitialWeights(phase, dd);

        StrategyState initialState = new StrategyState(
                asOfDate,
                ath,
                qqqClose,
                dd,
                maxDd,
                bucket,
                phase,
                targetWeights,
                true, // 초기에는 전략 ON
                CURRENT_STRATEGY_VERSION);

        log.info("Created initial StrategyState: ATH={}, DD={}%, Phase={}", ath, dd, phase);
        return strategyStateRepository.save(initialState);
    }

    private WeightSet decideInitialWeights(StrategyPhase phase, BigDecimal dd) {
        return switch (phase) {
            case NORMAL -> WeightSet.normal();
            case RECOVERY -> WeightSet.recovery();
            case DRAWDOWN -> WeightSet.drawDown(dd);
        };
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
                CURRENT_STRATEGY_VERSION);
    }

    private WeightSet decideTargetWeights(StrategyPhase phase, BigDecimal dd, StrategyState prev) {
        return switch (phase) {
            case NORMAL -> WeightSet.normal();
            case RECOVERY -> WeightSet.recovery();
            case DRAWDOWN ->
                dd.compareTo(BigDecimal.TEN) > 0 && dd.compareTo(BigDecimal.valueOf(15)) < 0 ? prev.targetWeights()
                        : WeightSet.drawDown(dd); // 10% < DD < 15% 이면 -> 이전 비중 유지
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
