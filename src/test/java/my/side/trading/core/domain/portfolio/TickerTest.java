package my.side.trading.core.domain.portfolio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TickerTest {

    @ParameterizedTest
    @ValueSource(strings = { "QQQM", "qqqm", "Qqqm", "QLD", "qld", "TQQQ", "tqqq" })
    void 대소문자_무관하게_Ticker_생성(String symbol) {
        Ticker ticker = Ticker.from(symbol);

        assertThat(ticker).isNotNull();
    }

    @Test
    void QQQM_심볼_변환() {
        assertThat(Ticker.from("QQQM")).isEqualTo(Ticker.QQQM);
        assertThat(Ticker.from("qqqm")).isEqualTo(Ticker.QQQM);
    }

    @Test
    void QLD_심볼_변환() {
        assertThat(Ticker.from("QLD")).isEqualTo(Ticker.QLD);
        assertThat(Ticker.from("qld")).isEqualTo(Ticker.QLD);
    }

    @Test
    void TQQQ_심볼_변환() {
        assertThat(Ticker.from("TQQQ")).isEqualTo(Ticker.TQQQ);
        assertThat(Ticker.from("tqqq")).isEqualTo(Ticker.TQQQ);
    }

    @Test
    void 지원하지_않는_심볼은_예외() {
        assertThatThrownBy(() -> Ticker.from("SPY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 심볼");
    }

    @Test
    void QQQ는_더_이상_운용심볼로_인정하지_않는다() {
        assertThatThrownBy(() -> Ticker.from("QQQ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 심볼");
    }

    @Test
    void null_또는_빈_문자열은_예외() {
        assertThatThrownBy(() -> Ticker.from(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol은 필수");

        assertThatThrownBy(() -> Ticker.from(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol은 필수");

        assertThatThrownBy(() -> Ticker.from("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol은 필수");
    }
}
