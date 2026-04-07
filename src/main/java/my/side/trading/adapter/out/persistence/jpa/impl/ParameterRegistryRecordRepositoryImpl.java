package my.side.trading.adapter.out.persistence.jpa.impl;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.persistence.jpa.entity.ParameterRegistryRecordEntity;
import my.side.trading.adapter.out.persistence.jpa.repository.ParameterRegistryRecordJpaRepository;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.domain.parameter.ParameterRegistryRecord;
import my.side.trading.core.domain.parameter.ParameterRegistryRecordRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ParameterRegistryRecordRepositoryImpl implements ParameterRegistryRecordRepository {

    private final ParameterRegistryRecordJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    @Override
    @Transactional
    public ParameterRegistryRecord save(ParameterRegistryRecord record) {
        return repository.save(ParameterRegistryRecordEntity.from(record)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParameterRegistryRecord> findAll() {
        return repository.findAll().stream()
                .map(ParameterRegistryRecordEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ParameterRegistryRecord> findByKey(ParameterRegistryKey key) {
        return repository.findById(key)
                .map(ParameterRegistryRecordEntity::toDomain);
    }
}
