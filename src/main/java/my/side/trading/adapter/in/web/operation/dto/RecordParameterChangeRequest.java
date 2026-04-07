package my.side.trading.adapter.in.web.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import my.side.trading.core.application.operation.RecordParameterChangeCommand;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.domain.parameter.ParameterRegistryStatus;

import java.time.LocalDate;
import java.util.List;

public record RecordParameterChangeRequest(
        @NotNull ParameterRegistryKey key,
        @NotBlank String observedEffectiveValue,
        @NotBlank String requestedBy,
        @NotBlank String reason,
        @NotNull ParameterRegistryStatus status,
        @NotBlank String basis,
        @NotBlank String validationMethod,
        @NotBlank String validationSummary,
        @NotNull LocalDate nextReviewDate,
        List<String> relatedArtifacts
) {
    public RecordParameterChangeCommand toCommand() {
        return new RecordParameterChangeCommand(
                key,
                observedEffectiveValue,
                requestedBy,
                reason,
                status,
                basis,
                validationMethod,
                validationSummary,
                nextReviewDate,
                relatedArtifacts == null ? List.of() : List.copyOf(relatedArtifacts));
    }
}
