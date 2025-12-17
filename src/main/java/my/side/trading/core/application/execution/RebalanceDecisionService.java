package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.execution.plan.RebalanceType;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RebalanceDecisionService {

    private static final BigDecimal TOLERANCE_PCT = new BigDecimal("5.0");     // ±5%
    private static final List<String> SYMBOLS = List.of("QQQ", "QLD", "TQQQ");

    public RebalanceDecision decide(StrategyState state, Portfolio portfolio) {

        // 전략 OFF면 리밸런싱 판단 자체를 하지 않음
        if (!state.strategyOn()) {
            return RebalanceDecision.no("strategyOn=false (NORMAL 구간): 리밸런싱 미수행");
        }

        BigDecimal total = portfolio.totalValue();
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return RebalanceDecision.no("총자산이 0 이하");
        }

        WeightSet target = state.targetWeights();
        // 실제 비중이 목표 비중 대비 ±5% 이상 이탈
        boolean exceeds = SYMBOLS.stream().anyMatch(sym -> {
            BigDecimal wTarget = targetWeightOf(target, sym);
            BigDecimal wActual = actualWeightOf(portfolio, sym);
            return wActual.subtract(wTarget).abs().compareTo(TOLERANCE_PCT) >= 0;
        });
        if (!exceeds) {
            return RebalanceDecision.no("비중 오차가 허용범위(±" + TOLERANCE_PCT + "%) 이내");
        }

        // 목표/실제 금액 비교로 주식 주문 리스트 반환
        List<OrderIntent> intents = buildIntents(target, portfolio);

        String reason = "비중 오차 초과로 리밸런싱 필요 (phase=" + state.phase() + ", ddBucket=" + state.ddBucket() + ")";
        return RebalanceDecision.yes(RebalanceType.THRESHOLD, reason, intents);
    }

    private List<OrderIntent> buildIntents(WeightSet target, Portfolio portfolio) {
        BigDecimal total = portfolio.totalValue();

        Map<String, BigDecimal> targetNotional = new HashMap<>();
        Map<String, BigDecimal> actualNotional = new HashMap<>();

        for (String sym : SYMBOLS) {
            // 각 종목별 목표금액
            BigDecimal wTarget = targetWeightOf(target, sym);
            targetNotional.put(sym, pctOf(total, wTarget));

            // 각 종목별 평가금액
            BigDecimal actualValue = portfolio.positions().stream()
                    .filter(p -> p.symbol().equals(sym))
                    .map(Position::value)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            actualNotional.put(sym, actualValue);
        }

        List<OrderIntent> intents = new ArrayList<>();
        for (String sym : SYMBOLS) {
            BigDecimal diff = targetNotional.get(sym).subtract(actualNotional.get(sym));

            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                intents.add(new OrderIntent(
                        sym, ExecutionOrderSide.BUY, diff.setScale(2, RoundingMode.HALF_UP),
                        "목표금액 (" + targetNotional.get(sym) + ") > 현재 보유금액 (" + actualNotional.get(sym) + ")"
                ));
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                intents.add(new OrderIntent(
                        sym, ExecutionOrderSide.SELL, diff.abs().setScale(2, RoundingMode.HALF_UP),
                        "목표금액 (" + targetNotional.get(sym) + ") < 현재 보유금액 (" + actualNotional.get(sym) + ")"
                ));
            }
        }
        return intents;
    }

    private BigDecimal actualWeightOf(Portfolio portfolio, String sym) {
        return switch (sym) {
            case "QQQ" -> portfolio.wQqq();
            case "QLD" -> portfolio.wQld();
            case "TQQQ" -> portfolio.wTqqq();
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal targetWeightOf(WeightSet target, String sym) {
        return switch (sym) {
            case "QQQ" -> target.wQqq();
            case "QLD" -> target.wQld();
            case "TQQQ" -> target.wTqqq();
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal pctOf(BigDecimal total, BigDecimal pct) {
        return total.multiply(pct)
                .divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
    }
}
