package my.side.trading.testutil;

import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeControlRepository;

import java.util.Optional;

public final class FakeOperatingModeControlRepository implements OperatingModeControlRepository {

    private OperatingMode currentMode;

    public FakeOperatingModeControlRepository() {
    }

    public FakeOperatingModeControlRepository(OperatingMode currentMode) {
        this.currentMode = currentMode;
    }

    @Override
    public Optional<OperatingMode> findCurrentMode() {
        return Optional.ofNullable(currentMode);
    }

    @Override
    public void saveCurrentMode(OperatingMode mode) {
        this.currentMode = mode;
    }
}
