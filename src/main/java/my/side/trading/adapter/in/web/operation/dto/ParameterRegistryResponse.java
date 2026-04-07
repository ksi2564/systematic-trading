package my.side.trading.adapter.in.web.operation.dto;

import my.side.trading.core.domain.parameter.ParameterRegistryRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ParameterRegistryResponse(
        String key,
        String displayName,
        String category,
        int sortOrder,
        String effectiveValue,
        String status,
        String basis,
        String validationMethod,
        String validationSummary,
        LocalDate nextReviewDate,
        List<String> relatedArtifacts,
        String lastChangedBy,
        Instant lastChangedAt
) {
    public static ParameterRegistryResponse from(ParameterRegistryRecord record) {
        return new ParameterRegistryResponse(
                record.key().name(),
                record.key().displayName(),
                record.key().category().name(),
                record.key().sortOrder(),
                record.effectiveValue(),
                record.status().name(),
                record.basis(),
                record.validationMethod(),
                record.validationSummary(),
                record.nextReviewDate(),
                record.relatedArtifacts(),
                record.lastChangedBy(),
                record.lastChangedAt());
    }
}
