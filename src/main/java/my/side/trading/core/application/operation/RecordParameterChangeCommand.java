package my.side.trading.core.application.operation;

import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.domain.parameter.ParameterRegistryStatus;

import java.time.LocalDate;
import java.util.List;

public record RecordParameterChangeCommand(
        ParameterRegistryKey key,
        String observedEffectiveValue,
        String requestedBy,
        String reason,
        ParameterRegistryStatus status,
        String basis,
        String validationMethod,
        String validationSummary,
        LocalDate nextReviewDate,
        List<String> relatedArtifacts
) {
}
