package my.side.trading.adapter.in.web.operation.dto;

import my.side.trading.core.domain.parameter.ParameterChangeEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ParameterChangeHistoryResponse(
        Long id,
        String key,
        String displayName,
        String category,
        int sortOrder,
        String previousValue,
        String newValue,
        String requestedBy,
        String reason,
        String status,
        String basis,
        String validationMethod,
        String validationSummary,
        LocalDate nextReviewDate,
        List<String> relatedArtifacts,
        Instant createdAt
) {
    public static ParameterChangeHistoryResponse from(ParameterChangeEvent event) {
        return new ParameterChangeHistoryResponse(
                event.id(),
                event.key().name(),
                event.key().displayName(),
                event.key().category().name(),
                event.key().sortOrder(),
                event.previousValue(),
                event.newValue(),
                event.requestedBy(),
                event.reason(),
                event.status().name(),
                event.basis(),
                event.validationMethod(),
                event.validationSummary(),
                event.nextReviewDate(),
                event.relatedArtifacts(),
                event.createdAt());
    }
}
