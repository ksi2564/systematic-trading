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
        String baseSymbol,
        String signalSymbol,
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
                preview.baseSymbol(),
                preview.signalSymbol(),
                StrategyInfo.from(preview.strategyState(), preview.baseSymbol()),
                DecisionInfo.from(preview.decision(), preview.baseSymbol()),
                PortfolioInfo.from(preview.portfolio(), preview.baseSymbol()),
                new MarketIndicatorInfo(preview.signalSymbol(), preview.vix(), preview.signal200Ma(), preview.signal200Ma()),
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
        static StrategyInfo from(StrategyState state, String baseSymbol) {
            return new StrategyInfo(
                    state.asOfDate(),
                    state.drawdownPct(),
                    state.ddBucket().name(),
                    state.phase().name(),
                    state.strategyOn(),
                    WeightInfo.from(state.targetWeights(), baseSymbol));
        }
    }

    public record DecisionInfo(
            boolean shouldRebalance,
            String type,
            String reason,
            WeightInfo targetWeights
    ) {
        static DecisionInfo from(my.side.trading.core.domain.execution.plan.RebalanceDecision decision, String baseSymbol) {
            return new DecisionInfo(
                    decision.shouldRebalance(),
                    decision.type() == null ? null : decision.type().name(),
                    decision.reason(),
                    WeightInfo.from(decision.targetWeights(), baseSymbol));
        }
    }

    public record PortfolioInfo(
            String baseSymbol,
            BigDecimal totalValue,
            BigDecimal cash,
            WeightInfo currentWeights
    ) {
        static PortfolioInfo from(Portfolio portfolio, String baseSymbol) {
            return new PortfolioInfo(
                    baseSymbol,
                    portfolio.totalValue(),
                    portfolio.cash(),
                    new WeightInfo(
                            baseSymbol,
                            portfolio.wBase(),
                            portfolio.wBase(),
                            portfolio.wQld(),
                            portfolio.wTqqq(),
                            portfolio.wBase(),
                            portfolio.wBase(),
                            portfolio.wQld(),
                            portfolio.wTqqq()));
        }
    }

    public record MarketIndicatorInfo(
            String signalSymbol,
            BigDecimal vix,
            BigDecimal signal200Ma,
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
            String baseSymbol,
            BigDecimal base,
            BigDecimal qqq,
            BigDecimal qld,
            BigDecimal tqqq,
            BigDecimal wBase,
            BigDecimal wQqq,
            BigDecimal wQld,
            BigDecimal wTqqq
    ) {
        static WeightInfo from(WeightSet weights, String baseSymbol) {
            if (weights == null) {
                return null;
            }
            return new WeightInfo(
                    baseSymbol,
                    weights.wBase(),
                    weights.wBase(),
                    weights.wQld(),
                    weights.wTqqq(),
                    weights.wBase(),
                    weights.wBase(),
                    weights.wQld(),
                    weights.wTqqq());
        }
    }
}
