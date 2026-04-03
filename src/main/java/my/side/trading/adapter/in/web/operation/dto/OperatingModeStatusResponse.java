package my.side.trading.adapter.in.web.operation.dto;

import my.side.trading.core.application.operation.OperatingModeChangeResult;
import my.side.trading.core.application.operation.OperatingModeStatus;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;

import java.util.List;

public record OperatingModeStatusResponse(
        OperatingMode currentMode,
        boolean changed,
        boolean manualApprovalRecorded,
        OperatingModeAuditEvent latestAuditEvent,
        List<OperatingModeAuditEvent> recentHistory
) {
    public static OperatingModeStatusResponse fromStatus(OperatingModeStatus status) {
        return new OperatingModeStatusResponse(
                status.currentMode(),
                false,
                status.manualApprovalRecorded(),
                status.recentHistory().isEmpty() ? null : status.recentHistory().getFirst(),
                status.recentHistory());
    }

    public static OperatingModeStatusResponse fromChangeResult(
            OperatingModeChangeResult result,
            List<OperatingModeAuditEvent> recentHistory
    ) {
        return new OperatingModeStatusResponse(
                result.currentMode(),
                result.changed(),
                result.manualApprovalRecorded(),
                result.auditEvent(),
                recentHistory);
    }
}
