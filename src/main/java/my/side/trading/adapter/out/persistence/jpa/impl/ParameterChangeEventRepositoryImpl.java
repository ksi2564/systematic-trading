package my.side.trading.adapter.out.persistence.jpa.impl;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.persistence.jpa.entity.ParameterChangeEventEntity;
import my.side.trading.adapter.out.persistence.jpa.repository.ParameterChangeEventJpaRepository;
import my.side.trading.core.domain.parameter.ParameterChangeEvent;
import my.side.trading.core.domain.parameter.ParameterChangeEventRepository;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ParameterChangeEventRepositoryImpl implements ParameterChangeEventRepository {

    private final ParameterChangeEventJpaRepository repository;

    @Override
    @Transactional
    public ParameterChangeEvent save(ParameterChangeEvent event) {
        return repository.save(ParameterChangeEventEntity.from(event)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParameterChangeEvent> findRecentByKey(ParameterRegistryKey key, int limit) {
        PageRequest pageRequest = PageRequest.of(
                0,
                limit,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return repository.findByKey(key, pageRequest).stream()
                .map(ParameterChangeEventEntity::toDomain)
                .toList();
    }
}
