package my.side.trading.core.application.strategy;

import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.config.TradingCircuitBreakerProps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerServiceTest {

    @Nested
    @DisplayName("VIX 필터 테스트")
    class VixFilterTest {

        @Test
        @DisplayName("VIX >= 35 이면 트리거됨")
        void vix_35이상이면_트리거() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);

            // 실행
            boolean triggered = service.isVixTriggered(new BigDecimal("35.00"));

            // 검증
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("VIX < 35 이면 트리거 안됨")
        void vix_35미만이면_트리거안됨() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);

            // 실행
            boolean triggered = service.isVixTriggered(new BigDecimal("34.99"));

            // 검증
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("VIX 필터 비활성화 시 트리거 안됨")
        void vix_비활성화시_트리거안됨() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(true, false, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);

            // 실행
            boolean triggered = service.isVixTriggered(new BigDecimal("50.00"));

            // 검증
            assertThat(triggered).isFalse();
        }

        @Test
        @DisplayName("Circuit Breaker 전체 비활성화 시 트리거 안됨")
        void 전체_비활성화시_트리거안됨() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(false, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);

            // 실행
            boolean triggered = service.isVixTriggered(new BigDecimal("50.00"));

            // 검증
            assertThat(triggered).isFalse();
        }
    }

    @Nested
    @DisplayName("200MA 필터 테스트")
    class MaFilterTest {

        @Test
        @DisplayName("QQQM < 200MA 이면 트리거됨")
        void qqqm_200ma미만이면_트리거() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);

            // 실행
            boolean triggered = service.isMaTriggered("QQQM", new BigDecimal("400"), new BigDecimal("410"));

            // 검증
            assertThat(triggered).isTrue();
        }

        @Test
        @DisplayName("QQQM >= 200MA 이면 트리거 안됨")
        void qqqm_200ma이상이면_트리거안됨() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);

            // 실행
            boolean triggered = service.isMaTriggered("QQQM", new BigDecimal("410"), new BigDecimal("400"));

            // 검증
            assertThat(triggered).isFalse();
        }
    }

    @Nested
    @DisplayName("비중 조정 테스트")
    class AdjustWeightsTest {

        @Test
        @DisplayName("200MA 트리거 시 TQQQ 비중 한 단계 낮춤")
        void ma_트리거시_tqqq_비중_감소() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);
            WeightSet original = WeightSet.of(20, 20, 60); // MORE_THAN_45 구간

            // 실행
            WeightSet adjusted = service.adjustWeights(original, null, false, true);

            // 검증
            assertThat(adjusted.wTqqq()).isEqualByComparingTo("40"); // 한 단계 낮춤
            assertThat(adjusted.wQqq()).isEqualByComparingTo("30");
            assertThat(adjusted.wQld()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("VIX 트리거 시 TQQQ 비중 증가 방지")
        void vix_트리거시_tqqq_증가_방지() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);
            WeightSet original = WeightSet.of(30, 30, 40); // 새로운 목표 비중
            WeightSet prevWeights = WeightSet.of(40, 40, 20); // 이전 비중 (TQQQ 20%)

            // 실행
            WeightSet adjusted = service.adjustWeights(original, prevWeights, true, false);

            // 검증
            // VIX 트리거로 이전 비중 유지 (TQQQ 증가 방지)
            assertThat(adjusted.wTqqq()).isEqualByComparingTo("20");
            assertThat(adjusted.wQqq()).isEqualByComparingTo("40");
            assertThat(adjusted.wQld()).isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("두 필터 모두 트리거 시 200MA 먼저 적용 후 VIX 체크")
        void 두_필터_모두_트리거() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);
            WeightSet original = WeightSet.of(20, 20, 60); // MORE_THAN_45 구간
            WeightSet prevWeights = WeightSet.of(30, 30, 40); // 이전 비중

            // 실행
            WeightSet adjusted = service.adjustWeights(original, prevWeights, true, true);

            // 검증
            // 200MA로 인해 60 -> 40으로 조정, VIX로 인해 40 <= 40이므로 그대로
            assertThat(adjusted.wTqqq()).isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("Circuit Breaker 비활성화 시 원본 비중 반환")
        void 비활성화시_원본_반환() {
            // 준비
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(false, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);
            WeightSet original = WeightSet.of(20, 20, 60);

            // 실행
            WeightSet adjusted = service.adjustWeights(original, null, true, true);

            // 검증
            assertThat(adjusted).isEqualTo(original);
        }

        @Test
        @DisplayName("VIX 트리거이지만 이전 비중이 없으면 현재 조정 비중 유지")
        void vix_트리거여도_prevWeights가_없으면_조정비중_유지() {
            TradingCircuitBreakerProps props = new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200);
            CircuitBreakerService service = new CircuitBreakerService(props);
            WeightSet original = WeightSet.of(30, 30, 40);

            WeightSet adjusted = service.adjustWeights(original, null, true, false);

            assertThat(adjusted).isEqualTo(original);
        }
    }
}
