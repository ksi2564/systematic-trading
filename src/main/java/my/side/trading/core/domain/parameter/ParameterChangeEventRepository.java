package my.side.trading.core.domain.parameter;

import java.util.List;

public interface ParameterChangeEventRepository {
    ParameterChangeEvent save(ParameterChangeEvent event);

    List<ParameterChangeEvent> findRecentByKey(ParameterRegistryKey key, int limit);
}
