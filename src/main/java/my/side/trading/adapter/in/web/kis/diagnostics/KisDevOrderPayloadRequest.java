package my.side.trading.adapter.in.web.kis.diagnostics;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;

import java.math.BigDecimal;
import java.util.Locale;

public record KisDevOrderPayloadRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z0-9.]{1,10}$", message = "symbol은 대문자 영문/숫자/점 1~10자로 입력해야 합니다.")
        String symbol,

        @NotNull
        ExecutionOrderSide side,

        @Min(1)
        long quantity,

        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal limitPrice
) {
    public String normalizedSymbol() {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
