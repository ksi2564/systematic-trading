package my.side.trading.core.application.operation;

import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;

import java.util.List;

public record OperatingModeStatus(
        OperatingMode currentMode,
        boolean manualApprovalRecorded,
        List<OperatingModeAuditEvent> recentHistory
) {
}
