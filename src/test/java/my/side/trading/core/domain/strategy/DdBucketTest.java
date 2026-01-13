package my.side.trading.core.domain.strategy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DdBucketTest {

    @ParameterizedTest(name = "ddPercent={0} -> {1}")
    @CsvSource({
            "0, LESS_THAN_15",
            "14.99, LESS_THAN_15",
            "15, FROM_15_TO_25",
            "15.00, FROM_15_TO_25",
            "24.99, FROM_15_TO_25",
            "25, FROM_25_TO_35",
            "34.99, FROM_25_TO_35",
            "35, FROM_35_TO_45",
            "44.99, FROM_35_TO_45",
            "45, MORE_THAN_45",
            "50, MORE_THAN_45",
            "100, MORE_THAN_45"
    })
    void DD_퍼센트에_따른_정확한_버킷_분류(String ddPct, DdBucket expected) {
        BigDecimal ddPercent = new BigDecimal(ddPct);

        DdBucket result = DdBucket.from(ddPercent);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void 경계값_15_퍼센트는_FROM_15_TO_25_버킷() {
        // 15% 정확히 경계값
        assertThat(DdBucket.from(new BigDecimal("15.00"))).isEqualTo(DdBucket.FROM_15_TO_25);
        assertThat(DdBucket.from(new BigDecimal("15"))).isEqualTo(DdBucket.FROM_15_TO_25);
    }

    @Test
    void 경계값_직전은_이전_버킷에_포함() {
        assertThat(DdBucket.from(new BigDecimal("14.9999"))).isEqualTo(DdBucket.LESS_THAN_15);
        assertThat(DdBucket.from(new BigDecimal("24.9999"))).isEqualTo(DdBucket.FROM_15_TO_25);
        assertThat(DdBucket.from(new BigDecimal("34.9999"))).isEqualTo(DdBucket.FROM_25_TO_35);
        assertThat(DdBucket.from(new BigDecimal("44.9999"))).isEqualTo(DdBucket.FROM_35_TO_45);
    }
}
