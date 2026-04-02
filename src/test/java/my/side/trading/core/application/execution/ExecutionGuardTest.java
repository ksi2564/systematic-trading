package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.guard.KillSwitchReader;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.infrastructure.config.TradingExecutionProps;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionGuardTest {

    @Test
    void execution_enabled_이고_killSwitch_off_이면_허용() {
        ExecutionGuard guard = createGuard(true, OperatingMode.AUTO_LIVE, false);

        // 예외 없이 통과해야 함
        guard.requireExecutionAllowed(ExecutionTriggerType.AUTOMATED);

        assertThat(guard.isExecutionEnabled()).isTrue();
        assertThat(guard.isKillSwitchOn()).isFalse();
    }

    @Test
    void paper_mode_이면_execution_enabled와_무관하게_실주문_차단() {
        ExecutionGuard guard = createGuard(true, OperatingMode.PAPER, false);

        assertThatThrownBy(() -> guard.requireExecutionAllowed(ExecutionTriggerType.MANUAL))
                .isInstanceOf(ExecutionBlockedException.class)
                .hasMessageContaining("PAPER_MODE_BLOCKS_LIVE_EXECUTION");
    }

    @Test
    void manual_live_에서는_자동실행_차단() {
        ExecutionGuard guard = createGuard(true, OperatingMode.MANUAL_LIVE, false);

        assertThatThrownBy(() -> guard.requireExecutionAllowed(ExecutionTriggerType.AUTOMATED))
                .isInstanceOf(ExecutionBlockedException.class)
                .hasMessageContaining("AUTO_EXECUTION_REQUIRES_AUTO_LIVE");
    }

    @Test
    void execution_disabled_이면_manual_live에서도_실행_차단() {
        ExecutionGuard guard = createGuard(false, OperatingMode.MANUAL_LIVE, false);

        assertThatThrownBy(() -> guard.requireExecutionAllowed(ExecutionTriggerType.MANUAL))
                .isInstanceOf(ExecutionBlockedException.class)
                .hasMessageContaining("EXECUTION_DISABLED");

        assertThat(guard.isExecutionEnabled()).isFalse();
    }

    @Test
    void killSwitch_on_이면_가장_우선해서_차단() {
        ExecutionGuard guard = createGuard(true, OperatingMode.AUTO_LIVE, true);

        assertThatThrownBy(() -> guard.requireExecutionAllowed(ExecutionTriggerType.AUTOMATED))
                .isInstanceOf(ExecutionBlockedException.class)
                .hasMessageContaining("KILL_SWITCH_ON");

        assertThat(guard.isKillSwitchOn()).isTrue();
    }

    private ExecutionGuard createGuard(boolean enabled, OperatingMode mode, boolean killSwitchOn) {
        TradingExecutionProps props = new TradingExecutionProps(enabled);
        TradingOperationProps operationProps = new TradingOperationProps(
                mode,
                new TradingOperationProps.AutoLiveGateProps(5, true, true, true));
        KillSwitchReader killSwitchReader = () -> killSwitchOn;
        return new ExecutionGuard(props, operationProps, killSwitchReader);
    }
}
