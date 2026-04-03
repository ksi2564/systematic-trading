package my.side.trading.adapter.in.web.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import my.side.trading.core.domain.operation.OperatingMode;

public record OperatingModeChangeRequest(
        @NotNull OperatingMode targetMode,
        @NotBlank String requestedBy,
        @NotBlank String reason
) {
}
