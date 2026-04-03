package my.side.trading.core.domain.operation;

import java.time.Instant;

public record OperatingModeAuditEvent(
        Long id,
        OperatingMode previousMode,
        OperatingMode targetMode,
        OperatingModeTransitionType transitionType,
        OperatingModeTriggerSource triggerSource,
        String triggerCode,
        String requestedBy,
        String reason,
        String approvedBy,
        Instant approvedAt,
        Instant createdAt
) {
    public OperatingModeAuditEvent {
        if (targetMode == null) {
            throw new IllegalArgumentException("targetMode is required");
        }
        if (transitionType == null) {
            throw new IllegalArgumentException("transitionType is required");
        }
        if (triggerSource == null) {
            throw new IllegalArgumentException("triggerSource is required");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
    }
}
