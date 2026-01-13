package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.strategy.CircuitBreakerService;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.execution.plan.RebalanceType;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.config.TradingStrategyProps;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class RebalanceDecisionService {

    private final TradingStrategyProps strategyProps;
    private final CircuitBreakerService circuitBreakerService;

    /**
     * 리밸런싱 판단 (기존 메서드 - Circuit Breaker 미적용)
     */
    public RebalanceDecision decide(StrategyState state, Portfolio portfolio) {
        return decide(state, portfolio, null, null);
    }

    /**
     * 리밸런싱 판단 (Circuit Breaker 적용)
     *
     * @param state     현재 전략 상태
     * @param portfolio 현재 포트폴리오
     * @param vix       VIX 지수 (없으면 null)
     * @param qqqMa200  QQQ 200MA (없으면 null)
     */
    public RebalanceDecision decide(StrategyState state, Portfolio portfolio, BigDecimal vix, BigDecimal qqqMa200) {

        // 전략 OFF면 리밸런싱 판단 자체를 하지 않음
        if (!state.strategyOn()) {
            return RebalanceDecision.no("strategyOn=false: 리밸런싱 미수행");
        }

        BigDecimal total = portfolio.totalValue();
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return RebalanceDecision.no("총자산이 0 이하");
        }

        WeightSet originalTarget = state.targetWeights();
        BigDecimal tolerancePct = strategyProps.tolerancePct();
        List<String> symbols = strategyProps.symbols();

        // Circuit Breaker 적용
        boolean vixTriggered = circuitBreakerService.isVixTriggered(vix);
        boolean maTriggered = circuitBreakerService.isMaTriggered(state.lastClose(), qqqMa200);
        WeightSet target = circuitBreakerService.adjustWeights(
                originalTarget, null, vixTriggered, maTriggered);

        // 실제 비중이 목표 비중 대비 ±tolerancePct% 이상 이탈
        boolean exceeds = symbols.stream().anyMatch(sym -> {
            BigDecimal wTarget = targetWeightOf(target, sym);
            BigDecimal wActual = actualWeightOf(portfolio, sym);
            return wActual.subtract(wTarget).abs().compareTo(tolerancePct) >= 0;
        });
        if (!exceeds) {
            return RebalanceDecision.no("비중 오차가 허용범위(±" + tolerancePct + "%) 이내");
        }

        // 목표/실제 금액 비교로 주식 주문 리스트 반환
        List<OrderIntent> intents = buildIntents(target, portfolio);

        String reason = "비중 오차 초과로 리밸런싱 필요 (phase=" + state.phase() + ", ddBucket=" + state.ddBucket() + ")";
        if (vixTriggered) {
            reason += " [VIX Circuit Breaker 활성]";
        }
        if (maTriggered) {
            reason += " [200MA Circuit Breaker 활성]";
        }
        return RebalanceDecision.yes(RebalanceType.THRESHOLD, reason, intents);
    }

    private List<OrderIntent> buildIntents(WeightSet target, Portfolio portfolio) {
        BigDecimal total = portfolio.totalValue();
        List<String> symbols = strategyProps.symbols();

        Map<String, BigDecimal> targetNotional = new HashMap<>();
        Map<String, BigDecimal> actualNotional = new HashMap<>();

        for (String sym : symbols) {
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
        for (String sym : symbols) {
            BigDecimal diff = targetNotional.get(sym).subtract(actualNotional.get(sym));

            if (diff.signum() > 0) {
                intents.add(new OrderIntent(
                        sym, ExecutionOrderSide.BUY, diff.setScale(2, RoundingMode.HALF_UP),
                        "목표금액 (" + targetNotional.get(sym) + ") > 현재 보유금액 (" + actualNotional.get(sym) + ")"));
            } else if (diff.signum() < 0) {
                intents.add(new OrderIntent(
                        sym, ExecutionOrderSide.SELL, diff.abs().setScale(2, RoundingMode.HALF_UP),
                        "목표금액 (" + targetNotional.get(sym) + ") < 현재 보유금액 (" + actualNotional.get(sym) + ")"));
            }
        }

        List<String> sellPriority = strategyProps.sellPriority();
        List<String> buyPriority = strategyProps.buyPriority();

        return Stream.concat(
                intents.stream()
                        .filter(i -> i.side() == ExecutionOrderSide.SELL)
                        .sorted(Comparator.comparingInt(i -> sellPriority.indexOf(i.symbol()))),
                intents.stream()
                        .filter(i -> i.side() == ExecutionOrderSide.BUY)
                        .sorted(Comparator.comparingInt(i -> buyPriority.indexOf(i.symbol()))))
                .toList();
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
