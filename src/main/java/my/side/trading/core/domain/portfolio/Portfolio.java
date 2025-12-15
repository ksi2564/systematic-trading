package my.side.trading.core.domain.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record Portfolio(
        BigDecimal cash,        // 현금 잔고
        List<Position> positions
) {

    public BigDecimal totalValue() {
        BigDecimal sum = cash != null ? cash : BigDecimal.ZERO;
        for (Position p : positions) {
            sum = sum.add(p.value());
        }
        return sum;
    }

    public BigDecimal weightOf(String symbol) {
        BigDecimal total = totalValue();
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal symbolValue = positions.stream()
                .filter(p -> p.symbol().equals(symbol))
                .map(Position::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // (평가금액 / 총자산) * 100, 소수점 4자리
        return symbolValue
                .divide(total, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal wQqq() {
        return weightOf("QQQ");
    }

    public BigDecimal wQld() {
        return weightOf("QLD");
    }

    public BigDecimal wTqqq() {
        return weightOf("TQQQ");
    }
}
