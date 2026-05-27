package my.side.trading.adapter.out.persistence.jpa.impl;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.adapter.out.persistence.jpa.entity.ExecutionJobEntity;
import my.side.trading.adapter.out.persistence.jpa.entity.ExecutionOrderEntity;
import my.side.trading.adapter.out.persistence.jpa.repository.ExecutionJobJpaRepository;
import my.side.trading.adapter.out.persistence.jpa.repository.ExecutionOrderJpaRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionJob> findAllBySignalDate(LocalDate signalDate) {
        return jobJpaRepository.findAllBySignalDate(signalDate).stream()
                .map(jobEntity -> {
                    List<ExecutionOrder> orders = orderJpaRepository.findAllByJobId(jobEntity.getId())
                            .stream()
                            .map(ExecutionOrderEntity::toDomain)
                            .toList();
                    return jobEntity.toDomain(orders);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionJob> findAll() {
        return jobJpaRepository.findAll().stream()
                .map(this::toDomainWithOrders)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionJob> findRecent(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, recentJobSort());
        return jobJpaRepository.findAllBy(pageRequest).stream()
                .map(this::toDomainWithOrders)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countAll() {
        return jobJpaRepository.count();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderWithJob> findOrdersByStatus(ExecutionOrderStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, confirmationOrderSort());
        return orderJpaRepository.findByStatus(status, pageRequest).stream()
                .map(orderEntity -> {
                    ExecutionOrder order = orderEntity.toDomain();
                    ExecutionJob job = orderEntity.getJob().toDomain(List.of(order));
                    return new OrderWithJob(job, order);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countOrdersByStatus(ExecutionOrderStatus status) {
        return orderJpaRepository.countByStatus(status);
    }

    private ExecutionJob toDomainWithOrders(ExecutionJobEntity jobEntity) {
        List<ExecutionOrder> orders = orderJpaRepository.findAllByJobId(jobEntity.getId())
                .stream()
                .map(ExecutionOrderEntity::toDomain)
                .toList();
        return jobEntity.toDomain(orders);
    }

    private Sort recentJobSort() {
        return Sort.by(
                Sort.Order.desc("signalDate"),
                Sort.Order.desc("executeAfter"),
                Sort.Order.desc("id"));
    }

    private Sort confirmationOrderSort() {
        return Sort.by(
                Sort.Order.desc("job.signalDate"),
                Sort.Order.desc("job.executeAfter"),
                Sort.Order.desc("job.id"),
                Sort.Order.desc("id"));
    }
}
