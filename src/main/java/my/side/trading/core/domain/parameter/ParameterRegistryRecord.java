package my.side.trading.core.domain.parameter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ParameterRegistryRecord(
        ParameterRegistryKey key,
        String effectiveValue,
        ParameterRegistryStatus status,
        String basis,
        String validationMethod,
        String validationSummary,
        LocalDate nextReviewDate,
        List<String> relatedArtifacts,
        String lastChangedBy,
        Instant lastChangedAt
) {
    public ParameterRegistryRecord {
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        if (effectiveValue == null || effectiveValue.isBlank()) {
            throw new IllegalArgumentException("effectiveValue is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException("basis is required");
        }
        if (validationMethod == null || validationMethod.isBlank()) {
            throw new IllegalArgumentException("validationMethod is required");
        }
        if (validationSummary == null || validationSummary.isBlank()) {
            throw new IllegalArgumentException("validationSummary is required");
        }
        if (nextReviewDate == null) {
            throw new IllegalArgumentException("nextReviewDate is required");
        }
        relatedArtifacts = relatedArtifacts == null ? List.of() : List.copyOf(relatedArtifacts);
        if (lastChangedBy == null || lastChangedBy.isBlank()) {
            throw new IllegalArgumentException("lastChangedBy is required");
        }
        if (lastChangedAt == null) {
            throw new IllegalArgumentException("lastChangedAt is required");
        }
    }
}
