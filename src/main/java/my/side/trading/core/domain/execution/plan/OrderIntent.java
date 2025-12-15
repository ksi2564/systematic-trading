package my.side.trading.core.domain.execution.plan;

import java.math.BigDecimal;

public record OrderIntent(
        String symbol,      // "QQQ" / "QLD" / "TQQQ"
        OrderSide side,     // 매수 / 매도
        BigDecimal notionalUsd,  // 금액(USD) 기준. 5단계에서 수량으로 변환
//        long quantity,      // 수량
        String reason       // "REBALANCE", "DD_CHANGE", "MONTHLY" 등
) {
}
