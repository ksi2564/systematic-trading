package my.side.trading.core.domain.strategy;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StrategyState(
        LocalDate asOfDate,                 // 상태 기준 일자 (EOD)
        BigDecimal ath,                     // QQQ 전고점 종가
        BigDecimal lastClose,               // 어제 QQQ 종가
        BigDecimal drawdownPct,             // DD 퍼센트 (예: 15.23 == 15.23%)
        BigDecimal maxDrawdownPctSinceAth,  // 해당 ATH 이후 경험한 최악의 DD (%)
        DdBucket ddBucket,                  // DD 구간
        StrategyPhase phase,                // 차트 상태에 따른 전략
        WeightSet targetWeights,            // 목표 비중
        boolean strategyOn,                 // 전략 ON/OFF
        int version                         // 전략 버전(기획서 버전 번호 등)
) {
}
