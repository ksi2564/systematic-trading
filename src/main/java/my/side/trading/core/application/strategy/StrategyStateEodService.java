package my.side.trading.core.application.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.DrawdownThresholds;
import my.side.trading.core.domain.strategy.QqqHistoricalDataProvider;
import my.side.trading.core.domain.strategy.RecoveryThresholds;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.config.TradingStrategyThresholdProps;
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
    private static final int ATH_LOOKUP_DAYS = 365;

    private final StrategyStateRepository strategyStateRepository;
    private final QqqHistoricalDataProvider qqqHistoricalDataProvider;
    private final TradingStrategyThresholdProps strategyThresholdProps;

    public StrategyState runEod(LocalDate asOfDate, BigDecimal qqqClose) {
        StrategyState prev = strategyStateRepository.findLatestState()
                .orElseGet(() -> initializeState(asOfDate, qqqClose));

        StrategyState newState = calculateNextState(asOfDate, qqqClose, prev);
        return strategyStateRepository.save(newState);
    }

    private StrategyState initializeState(LocalDate asOfDate, BigDecimal qqqClose) {
        log.info("과거 데이터로 StrategyState를 초기화합니다.");

        List<BigDecimal> historicalPrices = qqqHistoricalDataProvider.getHistoricalClosePrices(ATH_LOOKUP_DAYS);

        if (historicalPrices.isEmpty()) {
            log.warn("과거 데이터가 없어 현재 종가를 ATH로 사용합니다.");
            return createInitialState(asOfDate, qqqClose, qqqClose);
        }

        BigDecimal ath = historicalPrices.stream()
                .max(BigDecimal::compareTo)
                .orElse(qqqClose);

        if (qqqClose.compareTo(ath) > 0) {
            ath = qqqClose;
        }

        log.info("과거 가격 {}건으로 계산한 ATH={}", historicalPrices.size(), ath);
        return createInitialState(asOfDate, qqqClose, ath);
    }

    private StrategyState createInitialState(LocalDate asOfDate, BigDecimal qqqClose, BigDecimal ath) {
        BigDecimal dd = calculateDrawdownPercent(ath, qqqClose);
        BigDecimal maxDd = dd;
        DrawdownThresholds drawdownThresholds = strategyThresholdProps.drawdownThresholds();
        RecoveryThresholds recoveryThresholds = strategyThresholdProps.recoveryThresholds();

        DdBucket bucket = DdBucket.from(dd, drawdownThresholds);
        StrategyPhase phase = StrategyPhase.from(maxDd, dd, recoveryThresholds);
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
                true,
                CURRENT_STRATEGY_VERSION);

        log.info("초기 StrategyState를 생성했습니다: ATH={}, DD={}%, phase={}", ath, dd, phase);
        return strategyStateRepository.save(initialState);
    }

    private WeightSet decideInitialWeights(StrategyPhase phase, BigDecimal dd) {
        DrawdownThresholds drawdownThresholds = strategyThresholdProps.drawdownThresholds();
        return switch (phase) {
            case NORMAL -> WeightSet.normal();
            case RECOVERY -> WeightSet.recovery();
            case DRAWDOWN -> WeightSet.drawDown(dd, drawdownThresholds);
        };
    }

    private StrategyState calculateNextState(LocalDate asOfDate, BigDecimal qqqClose, StrategyState prev) {
        BigDecimal prevAth = prev.ath();
        BigDecimal ath;
        BigDecimal dd;
        BigDecimal maxDd;

        if (qqqClose.compareTo(prevAth) > 0) {
            ath = qqqClose;
            dd = BigDecimal.ZERO;
            maxDd = BigDecimal.ZERO;
        } else {
            ath = prevAth;
            dd = calculateDrawdownPercent(ath, qqqClose);
            maxDd = prev.maxDrawdownPctSinceAth().max(dd);
        }

        DrawdownThresholds drawdownThresholds = strategyThresholdProps.drawdownThresholds();
        RecoveryThresholds recoveryThresholds = strategyThresholdProps.recoveryThresholds();
        DdBucket bucket = DdBucket.from(dd, drawdownThresholds);
        StrategyPhase phase = StrategyPhase.from(maxDd, dd, recoveryThresholds);
        WeightSet targetWeights = decideTargetWeights(phase, dd, prev);
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
        DrawdownThresholds drawdownThresholds = strategyThresholdProps.drawdownThresholds();
        RecoveryThresholds recoveryThresholds = strategyThresholdProps.recoveryThresholds();
        return switch (phase) {
            case NORMAL -> WeightSet.normal();
            case RECOVERY -> WeightSet.recovery();
            case DRAWDOWN -> dd.compareTo(recoveryThresholds.recoveryDrawdownPct()) > 0
                    && dd.compareTo(recoveryThresholds.activationMaxDrawdownPct()) < 0
                            ? prev.targetWeights()
                            : WeightSet.drawDown(dd, drawdownThresholds);
        };
    }

    private BigDecimal calculateDrawdownPercent(BigDecimal ath, BigDecimal close) {
        if (ath.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = ath.subtract(close);
        return diff
                .divide(ath, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
