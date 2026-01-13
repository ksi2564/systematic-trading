package my.side.trading.adapter.out.persistence.jpa.impl;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.adapter.out.persistence.jpa.entity.ExecutionJobEntity;
import my.side.trading.adapter.out.persistence.jpa.entity.ExecutionOrderEntity;
import my.side.trading.adapter.out.persistence.jpa.repository.ExecutionJobJpaRepository;
import my.side.trading.adapter.out.persistence.jpa.repository.ExecutionOrderJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ExecutionJobRepositoryImpl implements ExecutionJobRepository {

    private final ExecutionJobJpaRepository jobJpaRepository;
    private final ExecutionOrderJpaRepository orderJpaRepository;

    @Override
    @Transactional
    public ExecutionJob save(ExecutionJob job) {
        // Job 저장
        ExecutionJobEntity savedJobEntity = jobJpaRepository.save(ExecutionJobEntity.from(job));

        // Orders 저장
        List<ExecutionOrderEntity> orderEntities = job.getOrders().stream()
                .map(o -> ExecutionOrderEntity.from(o, savedJobEntity))
                .toList();

        List<ExecutionOrderEntity> savedOrders = orderJpaRepository.saveAll(orderEntities);

        // 도메인으로 재조립해서 반환
        List<ExecutionOrder> domainOrders = savedOrders.stream()
                .map(ExecutionOrderEntity::toDomain)
                .toList();

        return savedJobEntity.toDomain(domainOrders);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionJob> findById(Long id) {
        return jobJpaRepository.findById(id).map(jobEntity -> {
            List<ExecutionOrder> orders = orderJpaRepository.findAllByJobId(jobEntity.getId())
                    .stream()
                    .map(ExecutionOrderEntity::toDomain)
                    .toList();
            return jobEntity.toDomain(orders);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionJob> findBySignalDate(LocalDate signalDate) {
        return jobJpaRepository.findBySignalDate(signalDate).map(jobEntity -> {
            List<ExecutionOrder> orders = orderJpaRepository.findAllByJobId(jobEntity.getId())
                    .stream()
                    .map(ExecutionOrderEntity::toDomain)
                    .toList();
            return jobEntity.toDomain(orders);
        });
    }
}
