package my.side.trading.core.domain.parameter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ParameterChangeEvent(
        Long id,
        ParameterRegistryKey key,
        String previousValue,
        String newValue,
        String requestedBy,
        String reason,
        ParameterRegistryStatus status,
        String basis,
        String validationMethod,
        String validationSummary,
        LocalDate nextReviewDate,
        List<String> relatedArtifacts,
        Instant createdAt
) {
    public ParameterChangeEvent {
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        if (newValue == null || newValue.isBlank()) {
            throw new IllegalArgumentException("newValue is required");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
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
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
    }
}
