package my.side.trading.core.domain.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record Portfolio(
        BigDecimal cash, // 현금 잔고
        List<Position> positions) {

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

    /**
     * Ticker Enum을 사용하는 타입 안전한 weightOf 메서드
     */
    public BigDecimal weightOf(Ticker ticker) {
        return weightOf(ticker.name());
    }

    public BigDecimal quantityOf(String symbol) {
        return positions.stream()
                .filter(p -> p.symbol().equals(symbol))
                .map(Position::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Ticker Enum을 사용하는 타입 안전한 quantityOf 메서드
     */
    public BigDecimal quantityOf(Ticker ticker) {
        return quantityOf(ticker.name());
    }

    public BigDecimal wBase() {
        return weightOf(Ticker.QQQM);
    }

    public BigDecimal wQqq() {
        return wBase();
    }

    public BigDecimal wQld() {
        return weightOf(Ticker.QLD);
    }

    public BigDecimal wTqqq() {
        return weightOf(Ticker.TQQQ);
    }
}
