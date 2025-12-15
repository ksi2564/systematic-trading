package my.side.trading.core.domain.portfolio;

import java.math.BigDecimal;

public record Position(
        String symbol,          // "QQQ", "QLD", "TQQQ"
        BigDecimal quantity,    // 보유 주수
        BigDecimal avgPrice,    // 평균 매입 단가
        BigDecimal marketPrice  // 현재가 (실시간 호가 캐시에서)
) {
    // 평가금액
    public BigDecimal value() {
        return marketPrice.multiply(quantity);
    }
}
