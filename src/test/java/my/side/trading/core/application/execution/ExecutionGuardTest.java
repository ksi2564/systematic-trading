package my.side.trading.core.application.execution;

import my.side.trading.core.domain.guard.KillSwitchReader;
import my.side.trading.core.infrastructure.config.TradingExecutionProps;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionGuardTest {

    @Test
    void execution_enabled_이고_killSwitch_off_이면_허용() {
        ExecutionGuard guard = createGuard(true, false);

        // 예외 없이 통과해야 함
        guard.requireExecutionAllowed();

        assertThat(guard.isExecutionEnabled()).isTrue();
        assertThat(guard.isKillSwitchOn()).isFalse();
    }

    @Test
    void execution_disabled_이면_예외_발생() {
        ExecutionGuard guard = createGuard(false, false);

        assertThatThrownBy(guard::requireExecutionAllowed)
                .isInstanceOf(ExecutionBlockedException.class)
                .hasMessageContaining("EXECUTION_DISABLED");

        assertThat(guard.isExecutionEnabled()).isFalse();
    }

    @Test
    void killSwitch_on_이면_예외_발생() {
        ExecutionGuard guard = createGuard(true, true);

        assertThatThrownBy(guard::requireExecutionAllowed)
                .isInstanceOf(ExecutionBlockedException.class)
                .hasMessageContaining("KILL_SWITCH_ON");

        assertThat(guard.isKillSwitchOn()).isTrue();
    }

    @Test
    void execution_disabled_우선_체크() {
        // 둘 다 꺼져있으면 EXECUTION_DISABLED 먼저 체크
        ExecutionGuard guard = createGuard(false, true);

        assertThatThrownBy(guard::requireExecutionAllowed)
                .isInstanceOf(ExecutionBlockedException.class)
                .hasMessageContaining("EXECUTION_DISABLED");
    }

    private ExecutionGuard createGuard(boolean enabled, boolean killSwitchOn) {
        TradingExecutionProps props = new TradingExecutionProps(enabled);
        KillSwitchReader killSwitchReader = () -> killSwitchOn;
        return new ExecutionGuard(props, killSwitchReader);
    }
}
