package my.side.trading.core.application.orchestration;

import my.side.trading.core.application.execution.ExecutionBlockReason;
import my.side.trading.core.application.execution.PlannedRebalanceOrder;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.StrategyState;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ManualRebalancePreview(
        Instant generatedAt,
        OperatingMode operatingMode,
        ExecutionBlockReason manualBlockReason,
        LocalDate signalDate,
        StrategyState strategyState,
        RebalanceDecision decision,
        Portfolio portfolio,
        BigDecimal vix,
        BigDecimal qqq200Ma,
        boolean duplicateSignalJobExists,
        List<PlannedRebalanceOrder> orders,
        BigDecimal totalOrderNotional,
        BigDecimal estimatedRemainingCash,
        boolean executable
) {
}
