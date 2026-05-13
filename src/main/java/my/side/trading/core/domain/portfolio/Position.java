package my.side.trading.core.domain.portfolio;

import java.math.BigDecimal;

public record Position(
        String symbol,          // "QQQM", "QLD", "TQQQ"
        BigDecimal quantity,    // 보유 주수
        BigDecimal avgPrice,    // 평균 매입 단가
        BigDecimal marketPrice  // 현재가 (실시간 호가 캐시에서 가져올 예정)
) {
    // 평가금액
    public BigDecimal value() {
        if (marketPrice == null) {
            throw new IllegalStateException("symbol: " + symbol + " | 실시간 호가 캐시에 marketPrice 가 없습니다.");
        }
        return marketPrice.multiply(quantity);
    }
}
