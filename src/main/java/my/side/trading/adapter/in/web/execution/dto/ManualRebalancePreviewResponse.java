package my.side.trading.adapter.in.web.execution.dto;

import my.side.trading.core.application.execution.ExecutionRiskViolation;
import my.side.trading.core.application.execution.PlannedRebalanceOrder;
import my.side.trading.core.application.orchestration.ManualRebalancePreview;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ManualRebalancePreviewResponse(
        Instant generatedAt,
        String operatingMode,
        String manualBlockReason,
        LocalDate signalDate,
        StrategyInfo strategy,
        DecisionInfo decision,
        PortfolioInfo portfolio,
        MarketIndicatorInfo marketIndicators,
        boolean duplicateSignalJobExists,
        List<OrderInfo> orders,
        BigDecimal totalOrderNotional,
        BigDecimal estimatedRemainingCash,
        boolean executable
) {
    public static ManualRebalancePreviewResponse from(ManualRebalancePreview preview) {
        return new ManualRebalancePreviewResponse(
                preview.generatedAt(),
                preview.operatingMode().name(),
                preview.manualBlockReason() == null ? null : preview.manualBlockReason().code(),
                preview.signalDate(),
                StrategyInfo.from(preview.strategyState()),
                DecisionInfo.from(preview.decision()),
                PortfolioInfo.from(preview.portfolio()),
                new MarketIndicatorInfo(preview.vix(), preview.qqq200Ma()),
                preview.duplicateSignalJobExists(),
                preview.orders().stream()
                        .map(OrderInfo::from)
                        .toList(),
                preview.totalOrderNotional(),
                preview.estimatedRemainingCash(),
                preview.executable());
    }

    public record StrategyInfo(
            LocalDate asOfDate,
            BigDecimal drawdownPct,
            String ddBucket,
            String phase,
            boolean strategyOn,
            WeightInfo targetWeights
    ) {
        static StrategyInfo from(StrategyState state) {
            return new StrategyInfo(
                    state.asOfDate(),
                    state.drawdownPct(),
                    state.ddBucket().name(),
                    state.phase().name(),
                    state.strategyOn(),
                    WeightInfo.from(state.targetWeights()));
        }
    }

    public record DecisionInfo(
            boolean shouldRebalance,
            String type,
            String reason,
            WeightInfo targetWeights
    ) {
        static DecisionInfo from(my.side.trading.core.domain.execution.plan.RebalanceDecision decision) {
            return new DecisionInfo(
                    decision.shouldRebalance(),
                    decision.type() == null ? null : decision.type().name(),
                    decision.reason(),
                    WeightInfo.from(decision.targetWeights()));
        }
    }

    public record PortfolioInfo(
            BigDecimal totalValue,
            BigDecimal cash,
            WeightInfo currentWeights
    ) {
        static PortfolioInfo from(Portfolio portfolio) {
            return new PortfolioInfo(
                    portfolio.totalValue(),
                    portfolio.cash(),
                    new WeightInfo(
                            portfolio.wQqq(),
                            portfolio.wQld(),
                            portfolio.wTqqq()));
        }
    }

    public record MarketIndicatorInfo(
            BigDecimal vix,
            BigDecimal qqq200Ma
    ) {
    }

    public record OrderInfo(
            String symbol,
            String side,
            long quantity,
            BigDecimal refPrice,
            BigDecimal limitPrice,
            BigDecimal notional,
            BigDecimal estimatedCashDelta,
            RiskInfo risk
    ) {
        static OrderInfo from(PlannedRebalanceOrder planned) {
            ExecutionOrder order = planned.order();
            return new OrderInfo(
                    order.getSymbol(),
                    order.getSide().name(),
                    order.getQuantity(),
                    order.getRefPrice(),
                    order.getLimitPrice(),
                    planned.notional(),
                    planned.estimatedCashDelta(),
                    RiskInfo.from(planned.riskViolation()));
        }
    }

    public record RiskInfo(
            String status,
            String type,
            BigDecimal actual,
            BigDecimal limit,
            String unit,
            String summary
    ) {
        static RiskInfo from(ExecutionRiskViolation violation) {
            if (violation == null) {
                return new RiskInfo("PASS", null, null, null, null, null);
            }
            return new RiskInfo(
                    "BLOCKED",
                    violation.type().name(),
                    violation.actual(),
                    violation.limit(),
                    violation.unit(),
                    violation.summary());
        }
    }

    public record WeightInfo(
            BigDecimal qqq,
            BigDecimal qld,
            BigDecimal tqqq
    ) {
        static WeightInfo from(WeightSet weights) {
            if (weights == null) {
                return null;
            }
            return new WeightInfo(weights.wQqq(), weights.wQld(), weights.wTqqq());
        }
    }
}
