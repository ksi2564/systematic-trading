package my.side.trading.core.application.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.side.trading.core.application.execution.RebalanceDecisionService;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.config.TradingCircuitBreakerProps;
import my.side.trading.core.infrastructure.config.TradingStrategyProps;
import my.side.trading.core.infrastructure.config.TradingStrategyThresholdProps;
import my.side.trading.testutil.FakeSignalHistoricalDataProvider;
import my.side.trading.testutil.FakeStrategyStateRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QqqmJavaPythonGoldenParityTest {

    private static JsonNode fixture;

    @BeforeAll
    static void loadFixture() throws IOException {
        try (InputStream input = QqqmJavaPythonGoldenParityTest.class.getResourceAsStream(
                "/strategy-parity/qqqm_java_python_golden.json")) {
            assertThat(input).isNotNull();
            fixture = new ObjectMapper().readTree(input);
        }
    }

    @Test
    void javaMatchesSharedDrawdownAndRecoveryGoldenCases() {
        StrategyState normal = strategyState(
                new BigDecimal("100"),
                new BigDecimal("95"),
                new BigDecimal("5.0000"),
                new BigDecimal("5.0000"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.normal());

        for (JsonNode scenario : fixture.get("drawdown_boundaries")) {
            StrategyState result = strategyService(new FakeStrategyStateRepository(normal))
                    .runEod(LocalDate.of(2026, 4, 1), decimal(scenario, "close"));

            assertThat(result.drawdownPct()).isEqualByComparingTo(scenario.get("expected_drawdown").asText());
            assertThat(result.ddBucket().name()).isEqualTo(scenario.get("expected_bucket").asText());
            assertThat(result.targetWeights()).isEqualTo(weightSet(scenario.get("expected_target")));
            assertTotalAllocation(result.targetWeights());
        }

        for (JsonNode scenario : fixture.get("recovery_hysteresis")) {
            JsonNode previous = scenario.get("previous");
            StrategyState previousState = strategyState(
                    decimal(previous, "ath"),
                    decimal(previous, "close"),
                    decimal(previous, "drawdown"),
                    decimal(previous, "max_drawdown"),
                    DdBucket.valueOf(previous.get("bucket").asText()),
                    StrategyPhase.valueOf(previous.get("phase").asText()),
                    weightSet(previous.get("target")));
            StrategyState result = strategyService(new FakeStrategyStateRepository(previousState))
                    .runEod(LocalDate.of(2026, 4, 2), decimal(scenario, "close"));

            assertThat(result.drawdownPct()).isEqualByComparingTo(scenario.get("expected_drawdown").asText());
            assertThat(result.phase().name()).isEqualTo(scenario.get("expected_phase").asText());
            assertThat(result.targetWeights()).isEqualTo(weightSet(scenario.get("expected_target")));
            assertTotalAllocation(result.targetWeights());
        }
    }

    @Test
    void javaMatchesSharedGuardBoundariesAndMaThenVixOrder() {
        CircuitBreakerService service = circuitBreakerService();
        for (JsonNode scenario : fixture.get("guard_boundaries")) {
            boolean maTriggered = service.isMaTriggered(
                    "QQQM",
                    decimal(scenario, "close"),
                    decimal(scenario, "ma"));
            boolean vixTriggered = service.isVixTriggered(decimal(scenario, "vix"));

            assertThat(maTriggered).isEqualTo(scenario.get("expected_ma_triggered").asBoolean());
            assertThat(vixTriggered).isEqualTo(scenario.get("expected_vix_triggered").asBoolean());
        }

        JsonNode scenario = fixture.get("ma_then_vix");
        WeightSet adjusted = service.adjustWeights(
                weightSet(scenario.get("original")),
                weightSet(scenario.get("previous_base")),
                service.isVixTriggered(decimal(scenario, "vix")),
                service.isMaTriggered("QQQM", decimal(scenario, "close"), decimal(scenario, "ma")));

        assertThat(adjusted).isEqualTo(weightSet(scenario.get("expected_target")));
        assertTotalAllocation(adjusted);
    }

    @Test
    void javaMatchesSharedFirstAndConsecutiveHighVixSequenceUsingPreviousBaseTarget() {
        CircuitBreakerService service = circuitBreakerService();
        WeightSet previousBase = null;

        for (JsonNode scenario : fixture.get("paper_sequence")) {
            WeightSet base = weightSet(scenario.get("expected_base"));
            WeightSet adjusted = service.adjustWeights(
                    base,
                    previousBase,
                    service.isVixTriggered(decimal(scenario, "vix")),
                    service.isMaTriggered("QQQM", decimal(scenario, "close"), decimal(scenario, "ma")));

            assertThat(adjusted).isEqualTo(weightSet(scenario.get("expected_target")));
            assertTotalAllocation(adjusted);
            previousBase = base;
        }
    }

    @Test
    void javaMatchesSharedOrderToleranceGateAndPriority() {
        for (JsonNode scenario : fixture.get("order_tolerance_and_priority")) {
            RebalanceDecisionService service = new RebalanceDecisionService(
                    new TradingStrategyProps(
                            decimal(scenario, "tolerance_pct"),
                            List.of("QQQM", "QLD", "TQQQ"),
                            List.of("TQQQ", "QLD", "QQQM"),
                            List.of("QQQM", "QLD", "TQQQ")),
                    new CircuitBreakerService(
                            new TradingCircuitBreakerProps(false, false, null, null)));
            StrategyState state = strategyState(
                    new BigDecimal("100"),
                    new BigDecimal("100"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    DdBucket.LESS_THAN_15,
                    StrategyPhase.NORMAL,
                    weightSet(scenario.get("target")));

            RebalanceDecision decision = service.decide(
                    state,
                    portfolio(scenario.get("portfolio")));

            List<String> actualIntents = decision.intents().stream()
                    .map(intent -> intent.symbol() + ":" + intent.side().name())
                    .toList();
            List<String> expectedIntents = new ArrayList<>();
            for (JsonNode intent : scenario.get("expected_intents")) {
                expectedIntents.add(intent.get("symbol").asText() + ":" + intent.get("side").asText());
            }

            assertThat(decision.shouldRebalance())
                    .as(scenario.get("id").asText())
                    .isEqualTo(scenario.get("expected_should_rebalance").asBoolean());
            assertThat(actualIntents)
                    .as(scenario.get("id").asText())
                    .containsExactlyElementsOf(expectedIntents);
        }
    }

    private static StrategyStateEodService strategyService(FakeStrategyStateRepository repository) {
        return new StrategyStateEodService(
                repository,
                new FakeSignalHistoricalDataProvider(),
                new TradingStrategyProps(null, "QQQM", null, null, null),
                new TradingStrategyThresholdProps(null, null));
    }

    private static CircuitBreakerService circuitBreakerService() {
        return new CircuitBreakerService(
                new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200));
    }

    private static StrategyState strategyState(
            BigDecimal ath,
            BigDecimal close,
            BigDecimal drawdown,
            BigDecimal maxDrawdown,
            DdBucket bucket,
            StrategyPhase phase,
            WeightSet target) {
        return new StrategyState(
                LocalDate.of(2026, 3, 31),
                "QQQM",
                ath,
                close,
                drawdown,
                maxDrawdown,
                bucket,
                phase,
                target,
                true,
                2);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private static WeightSet weightSet(JsonNode node) {
        return WeightSet.of(
                node.get("QQQM").asInt(),
                node.get("QLD").asInt(),
                node.get("TQQQ").asInt());
    }

    private static Portfolio portfolio(JsonNode node) {
        List<Position> positions = new ArrayList<>();
        for (JsonNode position : node.get("positions")) {
            positions.add(new Position(
                    position.get("symbol").asText(),
                    decimal(position, "quantity"),
                    BigDecimal.ZERO,
                    decimal(position, "market_price")));
        }
        return new Portfolio(decimal(node, "cash"), positions);
    }

    private static void assertTotalAllocation(WeightSet weights) {
        assertThat(weights.wBase().add(weights.wQld()).add(weights.wTqqq()))
                .isEqualByComparingTo("100");
    }
}
