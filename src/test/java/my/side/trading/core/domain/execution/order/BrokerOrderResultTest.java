package my.side.trading.core.domain.execution.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerOrderResultTest {

    @Test
    void 성공_결과는_accepted로_판별한다() {
        BrokerOrderResult result = BrokerOrderResult.success("ORD001", "ok");

        assertThat(result.type()).isEqualTo(BrokerOrderResult.ResultType.ACCEPTED);
        assertThat(result.success()).isTrue();
        assertThat(result.confirmationRequired()).isFalse();
    }

    @Test
    void 일반_실패는_rejected로_판별한다() {
        BrokerOrderResult result = BrokerOrderResult.failure(null, "failed");

        assertThat(result.type()).isEqualTo(BrokerOrderResult.ResultType.REJECTED);
        assertThat(result.success()).isFalse();
        assertThat(result.confirmationRequired()).isFalse();
    }

    @Test
    void 확인_필요는_confirmation_required로_판별한다() {
        BrokerOrderResult result = BrokerOrderResult.confirmationRequired(null, "timeout");

        assertThat(result.type()).isEqualTo(BrokerOrderResult.ResultType.CONFIRMATION_REQUIRED);
        assertThat(result.success()).isFalse();
        assertThat(result.confirmationRequired()).isTrue();
    }
}
