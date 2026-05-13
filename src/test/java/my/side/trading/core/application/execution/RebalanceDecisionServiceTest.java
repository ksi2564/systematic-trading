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
                                List.of("QQQM", "QLD", "TQQQ"),
                                List.of("TQQQ", "QLD", "QQQM"),
                                List.of("QQQM", "QLD", "TQQQ"));
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

                // 실제: QQQM 20%, QLD 10%, TQQQ 70% 같은 극단(의도적으로 허용 오차 초과)
                // 총자산=1000, 현금=0
                Portfolio portfolio = new Portfolio(
                                BigDecimal.ZERO,
                                List.of(
                                                new Position("QQQM", new BigDecimal("2"), new BigDecimal("0"),
                                                                new BigDecimal("100")), // 200
                                                new Position("QLD", new BigDecimal("1"), new BigDecimal("0"),
                                                                new BigDecimal("100")), // 100
                                                new Position("TQQQ", new BigDecimal("7"), new BigDecimal("0"),
                                                                new BigDecimal("100")) // 700
                                ));

                RebalanceDecision decision = service.decide(state, portfolio);

                assertThat(decision.shouldRebalance()).isTrue();
                assertThat(decision.intents()).isNotEmpty();
                // TQQQ 비중이 과다하므로 매도가 있어야 한다.
                assertThat(decision.intents()).anyMatch(i -> i.side() == ExecutionOrderSide.SELL);
                // QQQM, QLD 비중이 부족하므로 매수가 있어야 한다.
                assertThat(decision.intents()).anyMatch(i -> i.side() == ExecutionOrderSide.BUY);
                // "SELL 먼저, 그 다음 BUY" 순서를 검증한다.
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
                // 첫 intent는 SELL이어야 한다. (SELL이 반드시 있는 intents이므로)
                assertThat(decision.intents().get(0).side()).isEqualTo(ExecutionOrderSide.SELL);
        }

        @Test
        void 커스텀_tolerancePct_설정으로_리밸런싱_조건_변경() {
                // tolerancePct를 10%로 설정한다.
                TradingStrategyProps customProps = new TradingStrategyProps(
                                new BigDecimal("10.0"),
                                List.of("QQQM", "QLD", "TQQQ"),
                                List.of("TQQQ", "QLD", "QQQM"),
                                List.of("QQQM", "QLD", "TQQQ"));
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
                                WeightSet.of(100, 0, 0), // 목표: QQQM 100%
                                true,
                                1);

                // 실제: QQQM 92% -> 8% 편차이므로 10% tolerance 이내에서는 리밸런싱하지 않는다.
                Portfolio portfolio = new Portfolio(
                                new BigDecimal("80"), // 현금 8%
                                List.of(
                                                new Position("QQQM", new BigDecimal("9.2"), new BigDecimal("0"),
                                                                new BigDecimal("100")) // 920
                                ));

                RebalanceDecision decision = customService.decide(state, portfolio);

                assertThat(decision.shouldRebalance()).isFalse();
                assertThat(decision.reason()).contains("허용범위(±10.0%) 이내");
        }

        @Test
        void QQQ_보유분이_있으면_QQQM_전환_전_수동확인을_요구한다() {
                StrategyState state = new StrategyState(
                                LocalDate.of(2025, 12, 21),
                                "QQQM",
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                new BigDecimal("0.0000"),
                                new BigDecimal("0.0000"),
                                DdBucket.LESS_THAN_15,
                                StrategyPhase.NORMAL,
                                WeightSet.normal(),
                                true,
                                2);
                Portfolio portfolio = new Portfolio(
                                BigDecimal.ZERO,
                                List.of(new Position("QQQ", BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("700"))));

                RebalanceDecision decision = service.decide(state, portfolio);

                assertThat(decision.shouldRebalance()).isFalse();
                assertThat(decision.reason()).contains("QQQ 보유분");
        }

        @Test
        void vix_트리거시_이전비중보다_tqqq가_늘어나면_이전비중으로_제한한다() {
                TradingStrategyProps props = new TradingStrategyProps(
                                new BigDecimal("5.0"),
                                List.of("QQQM", "QLD", "TQQQ"),
                                List.of("TQQQ", "QLD", "QQQM"),
                                List.of("QQQM", "QLD", "TQQQ"));
                TradingCircuitBreakerProps cbProps = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
                RebalanceDecisionService customService = new RebalanceDecisionService(props, new CircuitBreakerService(cbProps));

                StrategyState state = new StrategyState(
                                LocalDate.of(2026, 4, 1),
                                new BigDecimal("500"),
                                new BigDecimal("420"),
                                new BigDecimal("16.0000"),
                                new BigDecimal("20.0000"),
                                DdBucket.FROM_15_TO_25,
                                StrategyPhase.DRAWDOWN,
                                WeightSet.of(30, 30, 40),
                                true,
                                1);
                Portfolio portfolio = new Portfolio(
                                BigDecimal.ZERO,
                                List.of(
                                                new Position("QQQM", new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("100")),
                                                new Position("QLD", new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("100")),
                                                new Position("TQQQ", new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("100"))));
                WeightSet prevWeights = WeightSet.of(60, 30, 10);

                RebalanceDecision decision = customService.decide(
                                state,
                                portfolio,
                                prevWeights,
                                new BigDecimal("40"),
                                new BigDecimal("400"));

                assertThat(decision.shouldRebalance()).isTrue();
                assertThat(decision.targetWeights()).isEqualTo(prevWeights);
                assertThat(decision.reason()).contains("VIX Circuit Breaker 활성");
        }

        @Test
        void ma와_vix가_동시에_트리거되면_ma를_먼저_적용한_후_vix를_적용한다() {
                TradingStrategyProps props = new TradingStrategyProps(
                                new BigDecimal("5.0"),
                                List.of("QQQM", "QLD", "TQQQ"),
                                List.of("TQQQ", "QLD", "QQQM"),
                                List.of("QQQM", "QLD", "TQQQ"));
                TradingCircuitBreakerProps cbProps = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
                RebalanceDecisionService customService = new RebalanceDecisionService(props, new CircuitBreakerService(cbProps));

                StrategyState state = new StrategyState(
                                LocalDate.of(2026, 4, 1),
                                new BigDecimal("500"),
                                new BigDecimal("390"),
                                new BigDecimal("22.0000"),
                                new BigDecimal("30.0000"),
                                DdBucket.FROM_25_TO_35,
                                StrategyPhase.DRAWDOWN,
                                WeightSet.of(20, 20, 60),
                                true,
                                1);
                Portfolio portfolio = new Portfolio(
                                BigDecimal.ZERO,
                                List.of(
                                                new Position("QQQM", new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("100")),
                                                new Position("QLD", new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("100")),
                                                new Position("TQQQ", new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("100"))));
                WeightSet prevWeights = WeightSet.of(30, 30, 40);

                RebalanceDecision decision = customService.decide(
                                state,
                                portfolio,
                                prevWeights,
                                new BigDecimal("38"),
                                new BigDecimal("400"));

                assertThat(decision.shouldRebalance()).isTrue();
                assertThat(decision.targetWeights()).isEqualTo(prevWeights);
                assertThat(decision.reason()).contains("VIX Circuit Breaker 활성");
                assertThat(decision.reason()).contains("200MA Circuit Breaker 활성");
        }

        @Test
        void 이전비중이_없어도_vix_트리거만으로_실패하지_않고_원래목표비중을_사용한다() {
                TradingStrategyProps props = new TradingStrategyProps(
                                new BigDecimal("5.0"),
                                List.of("QQQM", "QLD", "TQQQ"),
                                List.of("TQQQ", "QLD", "QQQM"),
                                List.of("QQQM", "QLD", "TQQQ"));
                TradingCircuitBreakerProps cbProps = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
                RebalanceDecisionService customService = new RebalanceDecisionService(props, new CircuitBreakerService(cbProps));

                StrategyState state = new StrategyState(
                                LocalDate.of(2026, 4, 1),
                                new BigDecimal("500"),
                                new BigDecimal("420"),
                                new BigDecimal("16.0000"),
                                new BigDecimal("20.0000"),
                                DdBucket.FROM_15_TO_25,
                                StrategyPhase.DRAWDOWN,
                                WeightSet.of(30, 30, 40),
                                true,
                                1);
                Portfolio portfolio = new Portfolio(
                                BigDecimal.ZERO,
                                List.of(
                                                new Position("QQQM", new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("100")),
                                                new Position("QLD", new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("100")),
                                                new Position("TQQQ", new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("100"))));

                RebalanceDecision decision = customService.decide(
                                state,
                                portfolio,
                                null,
                                new BigDecimal("40"),
                                new BigDecimal("400"));

                assertThat(decision.shouldRebalance()).isTrue();
                assertThat(decision.targetWeights()).isEqualTo(state.targetWeights());
        }
}
