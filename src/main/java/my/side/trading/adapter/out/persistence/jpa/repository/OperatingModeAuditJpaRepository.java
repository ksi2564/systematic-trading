package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.OperatingModeAuditEntity;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface OperatingModeAuditJpaRepository extends JpaRepository<OperatingModeAuditEntity, Long> {
    List<OperatingModeAuditEntity> findAllBy(Pageable pageable);

    boolean existsByTargetModeAndTriggerSourceAndApprovedAtIsNotNull(
            OperatingMode targetMode,
            OperatingModeTriggerSource triggerSource
    );
}
