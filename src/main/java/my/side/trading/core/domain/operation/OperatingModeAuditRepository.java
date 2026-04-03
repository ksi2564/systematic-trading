package my.side.trading.core.domain.operation;

import java.util.List;

public interface OperatingModeAuditRepository {
    OperatingModeAuditEvent save(OperatingModeAuditEvent event);

    List<OperatingModeAuditEvent> findRecent(int limit);

    boolean hasManualAutoLiveApproval();
}
