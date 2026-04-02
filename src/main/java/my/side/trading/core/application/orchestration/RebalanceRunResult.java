package my.side.trading.core.application.orchestration;

import my.side.trading.core.application.execution.ExecutionBlockReason;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.operation.OperatingMode;

public record RebalanceRunResult(
        ExecutionTriggerType triggerType,
        OperatingMode operatingMode,
        boolean jobCreated,
        boolean executed,
        Long jobId,
        String decisionReason,
        ExecutionBlockReason executionBlockReason) {

    public static RebalanceRunResult skipped(
            ExecutionTriggerType triggerType,
            OperatingMode operatingMode,
            String decisionReason) {
        return new RebalanceRunResult(triggerType, operatingMode, false, false, null, decisionReason, null);
    }

    public static RebalanceRunResult plannedOnly(
            ExecutionTriggerType triggerType,
            OperatingMode operatingMode,
            Long jobId,
            String decisionReason,
            ExecutionBlockReason executionBlockReason) {
        return new RebalanceRunResult(
                triggerType,
                operatingMode,
                true,
                false,
                jobId,
                decisionReason,
                executionBlockReason);
    }

    public static RebalanceRunResult executed(
            ExecutionTriggerType triggerType,
            OperatingMode operatingMode,
            Long jobId,
            String decisionReason) {
        return new RebalanceRunResult(triggerType, operatingMode, true, true, jobId, decisionReason, null);
    }
}
