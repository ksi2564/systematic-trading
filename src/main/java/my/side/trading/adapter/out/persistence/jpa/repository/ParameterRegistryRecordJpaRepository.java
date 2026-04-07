package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.ParameterRegistryRecordEntity;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParameterRegistryRecordJpaRepository
        extends JpaRepository<ParameterRegistryRecordEntity, ParameterRegistryKey> {
}
