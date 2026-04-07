package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.ParameterChangeEventEntity;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ParameterChangeEventJpaRepository extends JpaRepository<ParameterChangeEventEntity, Long> {
    List<ParameterChangeEventEntity> findByKey(ParameterRegistryKey key, Pageable pageable);
}
