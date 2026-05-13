package my.side.trading.core.domain.strategy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WeightSetTest {

    @Test
    void normal_상태는_기본자산_100퍼센트() {
        WeightSet weights = WeightSet.normal();

        assertThat(weights.wBase()).isEqualByComparingTo("100.00");
        assertThat(weights.wQld()).isEqualByComparingTo("0.00");
        assertThat(weights.wTqqq()).isEqualByComparingTo("0.00");
    }

    @Test
    void recovery_상태는_기본자산_70_QLD_30() {
        WeightSet weights = WeightSet.recovery();

        assertThat(weights.wBase()).isEqualByComparingTo("70.00");
        assertThat(weights.wQld()).isEqualByComparingTo("30.00");
        assertThat(weights.wTqqq()).isEqualByComparingTo("0.00");
    }

    @ParameterizedTest(name = "DD {0}% -> BASE:{1}, QLD:{2}, TQQQ:{3}")
    @CsvSource({
            "0, 100, 0, 0", // < 15%
            "10, 100, 0, 0", // < 15%
            "15, 60, 30, 10", // 15% ~ 25%
            "20, 60, 30, 10", // 15% ~ 25%
            "25, 40, 40, 20", // 25% ~ 35%
            "30, 40, 40, 20", // 25% ~ 35%
            "35, 30, 30, 40", // 35% ~ 45%
            "40, 30, 30, 40", // 35% ~ 45%
            "45, 20, 20, 60", // >= 45%
            "50, 20, 20, 60", // >= 45%
    })
    void drawDown_DD_퍼센트에_따른_기획서_비중표_일치(String ddPct, int base, int qld, int tqqq) {
        BigDecimal ddPercent = new BigDecimal(ddPct);

        WeightSet weights = WeightSet.drawDown(ddPercent);

        assertThat(weights.wBase()).isEqualByComparingTo(Integer.toString(base));
        assertThat(weights.wQld()).isEqualByComparingTo(Integer.toString(qld));
        assertThat(weights.wTqqq()).isEqualByComparingTo(Integer.toString(tqqq));
    }

    @Test
    void of_팩토리_메서드로_커스텀_비중_생성() {
        WeightSet weights = WeightSet.of(50, 30, 20);

        assertThat(weights.wBase()).isEqualByComparingTo("50.00");
        assertThat(weights.wQld()).isEqualByComparingTo("30.00");
        assertThat(weights.wTqqq()).isEqualByComparingTo("20.00");
    }
}
