package my.side.trading.adapter.out.control;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.persistence.jpa.entity.TradingControlEntity;
import my.side.trading.adapter.out.persistence.jpa.repository.TradingControlJpaRepository;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeControlRepository;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DbOperatingModeRepository implements OperatingModeControlRepository {

    private static final String KEY = "OPERATING_MODE";

    private final TradingControlJpaRepository repository;
    private final Clock clock;

    @Override
    public Optional<OperatingMode> findCurrentMode() {
        return repository.findById(KEY)
                .map(TradingControlEntity::getValue)
                .map(OperatingMode::valueOf);
    }

    @Override
    public void saveCurrentMode(OperatingMode mode) {
        repository.save(TradingControlEntity.builder()
                .key(KEY)
                .value(mode.name())
                .updatedAt(Instant.now(clock))
                .build());
    }
}
