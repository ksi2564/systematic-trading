package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ExecutionRiskLimitService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TradingOperationProps operationProps;
    private final ExecutionJobRepository jobRepository;

    public void validatePlannedOrder(
            LocalDate signalDate,
            Portfolio portfolio,
            ExecutionOrder order,
            BigDecimal plannedJobNotionalSoFar) {
        plannedOrderViolation(signalDate, portfolio, order, plannedJobNotionalSoFar)
                .ifPresent(violation -> {
                    throw new ExecutionRiskLimitExceededException(violation);
                });
    }

    public Optional<ExecutionRiskViolation> plannedOrderViolation(
            LocalDate signalDate,
            Portfolio portfolio,
            ExecutionOrder order,
            BigDecimal plannedJobNotionalSoFar) {
        BigDecimal orderNotional = orderNotional(order);
        TradingOperationProps.RiskLimitProps limits = operationProps.riskLimits();

        if (isEnabled(limits.maxOrderNotionalUsd()) && orderNotional.compareTo(limits.maxOrderNotionalUsd()) > 0) {
            return Optional.of(new ExecutionRiskViolation(
                    ExecutionRiskViolationType.ORDER_NOTIONAL,
                    orderNotional,
                    limits.maxOrderNotionalUsd(),
                    "USD"));
        }

        if (!isEnabled(limits.maxDailyTurnoverPct())) {
            return Optional.empty();
        }

        BigDecimal portfolioValue = portfolio.totalValue();
        if (portfolioValue == null || portfolioValue.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal existingNotional = jobRepository.findAllBySignalDate(signalDate).stream()
                .flatMap(job -> job.getOrders().stream())
                .filter(orderItem -> orderItem.getStatus() != ExecutionOrderStatus.SKIPPED)
                .map(this::orderNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalNotional = existingNotional
                .add(defaultZero(plannedJobNotionalSoFar))
                .add(orderNotional);
        BigDecimal turnoverPct = totalNotional
                .divide(portfolioValue, 8, RoundingMode.HALF_UP)
                .multiply(HUNDRED);

        if (turnoverPct.compareTo(limits.maxDailyTurnoverPct()) > 0) {
            return Optional.of(new ExecutionRiskViolation(
                    ExecutionRiskViolationType.DAILY_TURNOVER,
                    turnoverPct,
                    limits.maxDailyTurnoverPct(),
                    "%"));
        }

        return Optional.empty();
    }

    public Optional<ExecutionRiskViolation> retryExposureViolation(BigDecimal projectedExposureUsd) {
        TradingOperationProps.RiskLimitProps limits = operationProps.riskLimits();
        if (!isEnabled(limits.maxRetryExposureUsd())) {
            return Optional.empty();
        }
        if (defaultZero(projectedExposureUsd).compareTo(limits.maxRetryExposureUsd()) > 0) {
            return Optional.of(new ExecutionRiskViolation(
                    ExecutionRiskViolationType.RETRY_EXPOSURE,
                    projectedExposureUsd,
                    limits.maxRetryExposureUsd(),
                    "USD"));
        }
        return Optional.empty();
    }

    public Optional<ExecutionRiskViolation> slippageViolation(ExecutionOrder order, ExecutionResult result) {
        TradingOperationProps.RiskLimitProps limits = operationProps.riskLimits();
        if (!isEnabled(limits.maxSlippagePct()) || result.filledQty() <= 0) {
            return Optional.empty();
        }

        BigDecimal refPrice = order.getRefPrice();
        if (refPrice == null || refPrice.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal averageFillPrice = result.filledAmount()
                .divide(BigDecimal.valueOf(result.filledQty()), 8, RoundingMode.HALF_UP);
        BigDecimal adverseMove = order.getSide() == ExecutionOrderSide.BUY
                ? averageFillPrice.subtract(refPrice)
                : refPrice.subtract(averageFillPrice);

        if (adverseMove.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal slippagePct = adverseMove
                .divide(refPrice, 8, RoundingMode.HALF_UP)
                .multiply(HUNDRED);

        if (slippagePct.compareTo(limits.maxSlippagePct()) > 0) {
            return Optional.of(new ExecutionRiskViolation(
                    ExecutionRiskViolationType.SLIPPAGE,
                    slippagePct,
                    limits.maxSlippagePct(),
                    "%"));
        }
        return Optional.empty();
    }

    public BigDecimal orderNotional(ExecutionOrder order) {
        return order.getLimitPrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    }

    private boolean isEnabled(BigDecimal limit) {
        return limit != null && limit.signum() > 0;
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
