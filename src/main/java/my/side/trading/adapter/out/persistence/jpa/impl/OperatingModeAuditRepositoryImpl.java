package my.side.trading.adapter.out.persistence.jpa.impl;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.persistence.jpa.entity.OperatingModeAuditEntity;
import my.side.trading.adapter.out.persistence.jpa.repository.OperatingModeAuditJpaRepository;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import my.side.trading.core.domain.operation.OperatingModeAuditRepository;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class OperatingModeAuditRepositoryImpl implements OperatingModeAuditRepository {

    private final OperatingModeAuditJpaRepository repository;

    @Override
    @Transactional
    public OperatingModeAuditEvent save(OperatingModeAuditEvent event) {
        return repository.save(OperatingModeAuditEntity.from(event)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperatingModeAuditEvent> findRecent(int limit) {
        PageRequest pageRequest = PageRequest.of(
                0,
                limit,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return repository.findAllBy(pageRequest).stream()
                .map(OperatingModeAuditEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasManualAutoLiveApproval() {
        return repository.existsByTargetModeAndTriggerSourceAndApprovedAtIsNotNull(
                OperatingMode.AUTO_LIVE,
                OperatingModeTriggerSource.MANUAL_API);
    }
}
