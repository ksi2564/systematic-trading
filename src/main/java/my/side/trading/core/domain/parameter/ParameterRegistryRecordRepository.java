package my.side.trading.core.domain.parameter;

import java.util.List;
import java.util.Optional;

public interface ParameterRegistryRecordRepository {
    long count();

    ParameterRegistryRecord save(ParameterRegistryRecord record);

    List<ParameterRegistryRecord> findAll();

    Optional<ParameterRegistryRecord> findByKey(ParameterRegistryKey key);
}
