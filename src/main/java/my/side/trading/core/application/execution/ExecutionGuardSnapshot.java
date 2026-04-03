package my.side.trading.core.application.execution;

import my.side.trading.core.domain.operation.OperatingMode;

public record ExecutionGuardSnapshot(
        OperatingMode operatingMode,
        boolean executionEnabled,
        boolean killSwitchOn,
        ExecutionBlockReason manualBlockReason,
        ExecutionBlockReason automatedBlockReason,
        AutoLiveGateSnapshot autoLiveGate) {

    public record AutoLiveGateSnapshot(
            int requiredConsecutiveEodSuccessDays,
            boolean requireZeroPendingOrders,
            boolean requireZeroDuplicateSignalJobs,
            boolean requireManualApprovalRecord,
            boolean manualApprovalRecorded) {
    }
}
