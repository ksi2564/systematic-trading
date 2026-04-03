package my.side.trading.core.domain.operation;

import java.util.Optional;

public interface OperatingModeControlRepository {
    Optional<OperatingMode> findCurrentMode();

    void saveCurrentMode(OperatingMode mode);
}
