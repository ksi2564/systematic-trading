package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.guard.KillSwitchReader;
import my.side.trading.core.infrastructure.config.TradingExecutionProps;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExecutionGuard {

    private final TradingExecutionProps executionProps;
    private final KillSwitchReader killSwitchReader;

    public void requireExecutionAllowed() {
        if (!executionProps.enabled()) {
            throw new ExecutionBlockedException("EXECUTION_DISABLED");
        }
        if (killSwitchReader.isKillSwitchOn()) {
            throw new ExecutionBlockedException("KILL_SWITCH_ON");
        }
    }

    public boolean isExecutionEnabled() {
        return executionProps.enabled();
    }

    public boolean isKillSwitchOn() {
        return killSwitchReader.isKillSwitchOn();
    }
}
