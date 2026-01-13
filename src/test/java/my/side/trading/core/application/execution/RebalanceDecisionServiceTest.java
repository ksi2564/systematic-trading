package my.side.trading.core.application.execution;

import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.application.strategy.CircuitBreakerService;
import my.side.trading.core.infrastructure.config.TradingCircuitBreakerProps;
import my.side.trading.core.infrastructure.config.TradingStrategyProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RebalanceDecisionServiceTest {

        private RebalanceDecisionService service;

        @BeforeEach
        void setUp() {
                // 기본 설정값 사용
                TradingStrategyProps props = new TradingStrategyProps(
                                new BigDecimal("5.0"),
                                List.of("QQQ", "QLD", "TQQQ"),
                                List.of("TQQQ", "QLD", "QQQ"),
                                List.of("QQQ", "QLD", "TQQQ"));
                TradingCircuitBreakerProps cbProps = new TradingCircuitBreakerProps(false, false, null, null);
                CircuitBreakerService cbService = new CircuitBreakerService(cbProps);
                service = new RebalanceDecisionService(props, cbService);
        }

        @Test
        void 전략_비활성화_시_리밸런싱_안함() {
                StrategyState state = new StrategyState(
                                LocalDate.of(2025, 12, 21),
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                new BigDecimal("0.0000"),
                                new BigDecimal("0.0000"),
                                DdBucket.LESS_THAN_15,
                                StrategyPhase.NORMAL,
                                WeightSet.normal(),
                                false,
                                1);

                Portfolio portfolio = new Portfolio(new BigDecimal("1000"), List.of());

                RebalanceDecision decision = service.decide(state, portfolio);

                assertThat(decision.shouldRebalance()).isFalse();
                assertThat(decision.reason()).contains("strategyOn=false");
        }

        @Test
        void 비중편차가_tolerance를_초과하면_리밸런싱_수행하면_SELL이_BUY보다_먼저_수행() {
                StrategyState state = new StrategyState(
                                LocalDate.of(2025, 12, 21),
                                new BigDecimal("100"),
                                new BigDecimal("80"),
                                new BigDecimal("20.0000"),
                                new BigDecimal("20.0000"),
                                DdBucket.FROM_15_TO_25,
                                StrategyPhase.DRAWDOWN,
                                WeightSet.of(60, 30, 10),
                                true,
                                1);

                // 실제: QQQ 20%, QLD 10%, TQQQ 70% 같은 극단(의도적으로 tolerance 초과)
                // total=1000, cash=0
                Portfolio portfolio = new Portfolio(
                                BigDecimal.ZERO,
                                List.of(
                                                new Position("QQQ", new BigDecimal("2"), new BigDecimal("0"),
                                                                new BigDecimal("100")), // 200
                                                new Position("QLD", new BigDecimal("1"), new BigDecimal("0"),
                                                                new BigDecimal("100")), // 100
                                                new Position("TQQQ", new BigDecimal("7"), new BigDecimal("0"),
                                                                new BigDecimal("100")) // 700
                                ));

                RebalanceDecision decision = service.decide(state, portfolio);

                assertThat(decision.shouldRebalance()).isTrue();
                assertThat(decision.intents()).isNotEmpty();
                // TQQQ 비중 과다로 판매가 있어야함
                assertThat(decision.intents()).anyMatch(i -> i.side() == ExecutionOrderSide.SELL);
                // QQQ, QLD 비중 부족으로 구매가 있어야함
                assertThat(decision.intents()).anyMatch(i -> i.side() == ExecutionOrderSide.BUY);
                // "SELL 먼저, 그 다음 BUY"를 검증
                boolean seenBuy = false;
                for (OrderIntent i : decision.intents()) {
                        if (i.side() == ExecutionOrderSide.BUY) {
                                seenBuy = true;
                        }
                        if (seenBuy) {
                                assertThat(i.side())
                                                .as("BUY가 시작된 이후에는 SELL이 다시 나오면 안 된다. intents=%s", decision.intents())
                                                .isEqualTo(ExecutionOrderSide.BUY);
                        }
                }
                // 첫 intent는 SELL이어야 한다 (SELL이 무조건 있는 intents이므로)
                assertThat(decision.intents().get(0).side()).isEqualTo(ExecutionOrderSide.SELL);
        }

        @Test
        void 커스텀_tolerancePct_설정으로_리밸런싱_조건_변경() {
                // tolerancePct를 10%로 설정
                TradingStrategyProps customProps = new TradingStrategyProps(
                                new BigDecimal("10.0"),
                                List.of("QQQ", "QLD", "TQQQ"),
                                List.of("TQQQ", "QLD", "QQQ"),
                                List.of("QQQ", "QLD", "TQQQ"));
                TradingCircuitBreakerProps cbProps = new TradingCircuitBreakerProps(false, false, null, null);
                CircuitBreakerService cbService = new CircuitBreakerService(cbProps);
                RebalanceDecisionService customService = new RebalanceDecisionService(customProps, cbService);

                StrategyState state = new StrategyState(
                                LocalDate.of(2025, 12, 21),
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                new BigDecimal("0.0000"),
                                new BigDecimal("0.0000"),
                                DdBucket.LESS_THAN_15,
                                StrategyPhase.NORMAL,
                                WeightSet.of(100, 0, 0), // 목표: QQQ 100%
                                true,
                                1);

                // 실제: QQQ 92% -> 8% 편차, 10% tolerance 이내이므로 리밸런싱 안함
                Portfolio portfolio = new Portfolio(
                                new BigDecimal("80"), // 현금 8%
                                List.of(
                                                new Position("QQQ", new BigDecimal("9.2"), new BigDecimal("0"),
                                                                new BigDecimal("100")) // 920
                                ));

                RebalanceDecision decision = customService.decide(state, portfolio);

                assertThat(decision.shouldRebalance()).isFalse();
                assertThat(decision.reason()).contains("허용범위(±10.0%) 이내");
        }
}
