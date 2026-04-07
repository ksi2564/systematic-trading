package my.side.trading.core.application.operation;

import my.side.trading.core.domain.parameter.ParameterRegistryKey;

import java.util.Map;

public interface EffectiveParameterSnapshotProvider {
    Map<ParameterRegistryKey, String> snapshotAll();

    default String snapshot(ParameterRegistryKey key) {
        String value = snapshotAll().get(key);
        if (value == null) {
            throw new IllegalArgumentException("effective parameter snapshot is missing: " + key);
        }
        return value;
    }
}
