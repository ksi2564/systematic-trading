package my.side.trading.core.application.operation;

import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;

public record OperatingModeChangeResult(
        OperatingMode currentMode,
        boolean changed,
        boolean manualApprovalRecorded,
        OperatingModeAuditEvent auditEvent
) {
}
