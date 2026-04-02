package my.side.trading.core.application.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record ExecutionRiskViolation(
        ExecutionRiskViolationType type,
        BigDecimal actual,
        BigDecimal limit,
        String unit
) {
    public String summary() {
        return "%s actual=%s%s limit=%s%s".formatted(
                type.name(),
                format(actual),
                unit,
                format(limit),
                unit);
    }

    private String format(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
