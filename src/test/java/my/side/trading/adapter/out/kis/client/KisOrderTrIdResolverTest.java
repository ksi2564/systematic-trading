package my.side.trading.adapter.out.kis.client;

import my.side.trading.adapter.out.kis.config.KisProps;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KisOrderTrIdResolverTest {

    @Test
    void 실전_미국주문_tr_id를_반환한다() {
        KisOrderTrIdResolver resolver = new KisOrderTrIdResolver(props("https://openapi.koreainvestment.com:9443"));

        assertThat(resolver.resolveUsOrderTrId(ExecutionOrderSide.BUY)).isEqualTo("TTTT1002U");
        assertThat(resolver.resolveUsOrderTrId(ExecutionOrderSide.SELL)).isEqualTo("TTTT1006U");
        assertThat(resolver.resolveCancelTrId()).isEqualTo("TTTT1004U");
    }

    @Test
    void 모의투자_미국주문_tr_id를_반환한다() {
        KisOrderTrIdResolver resolver = new KisOrderTrIdResolver(props("https://openapivts.koreainvestment.com:29443"));

        assertThat(resolver.resolveUsOrderTrId(ExecutionOrderSide.BUY)).isEqualTo("VTTT1002U");
        assertThat(resolver.resolveUsOrderTrId(ExecutionOrderSide.SELL)).isEqualTo("VTTT1001U");
        assertThat(resolver.resolveCancelTrId()).isEqualTo("VTTT1004U");
    }

    private KisProps props(String baseUrl) {
        return new KisProps(
                baseUrl,
                "app-key",
                "app-secret",
                "account-no",
                "12345678",
                "01",
                null,
                null
        );
    }
}
