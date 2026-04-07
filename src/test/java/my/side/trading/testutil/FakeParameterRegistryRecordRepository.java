package my.side.trading.testutil;

import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.domain.parameter.ParameterRegistryRecord;
import my.side.trading.core.domain.parameter.ParameterRegistryRecordRepository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FakeParameterRegistryRecordRepository implements ParameterRegistryRecordRepository {

    private final Map<ParameterRegistryKey, ParameterRegistryRecord> records =
            new EnumMap<>(ParameterRegistryKey.class);

    @Override
    public long count() {
        return records.size();
    }

    @Override
    public ParameterRegistryRecord save(ParameterRegistryRecord record) {
        records.put(record.key(), record);
        return record;
    }

    @Override
    public List<ParameterRegistryRecord> findAll() {
        return new ArrayList<>(records.values());
    }

    @Override
    public Optional<ParameterRegistryRecord> findByKey(ParameterRegistryKey key) {
        return Optional.ofNullable(records.get(key));
    }
}
