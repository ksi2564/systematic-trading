package my.side.trading.adapter.out.kis.order;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.client.KisOverseasCcnlService;
import my.side.trading.adapter.out.kis.client.KisOverseasNccsService;
import my.side.trading.adapter.out.kis.dto.KisOverseasCcnlResponse;
import my.side.trading.adapter.out.kis.dto.KisOverseasNccsResponse;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.OrderInquiry;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class KisOrderInquiry implements OrderInquiry {

    // TODO: 추후 order에 exchange를 넣거나 Portfolio/Strategy에서 주입
    private static final String DEFAULT_EXCHANGE = "NASD";

    private final KisOverseasNccsService nccsService;
    private final KisOverseasCcnlService ccnlService;

    @Override
    public OrderInquiryResult confirm(ExecutionOrder order) {
        String symbol = order.getSymbol();
        String sideCode = (order.getSide() == ExecutionOrderSide.BUY) ? "02" : "01";

        // 미체결에서 먼저 찾기
        KisOverseasNccsResponse nccs = nccsService.inquireNccs(DEFAULT_EXCHANGE);
        if (nccs != null && "0".equals(nccs.resultCode()) && nccs.output() != null) {
            Optional<KisOverseasNccsResponse.Item> hit = nccs.output().stream()
                    .filter(i -> symbol.equals(i.pdno()))
                    .filter(i -> sideCode.equals(i.sideCode()))
                    .findFirst();

            if (hit.isPresent()) {
                return OrderInquiryResult.found(hit.get().orderNo(), "미체결내역 조회됨");
            }
        }

        // 주문체결내역에서 찾기(체결/미체결 포함)
        KisOverseasCcnlResponse ccnl = ccnlService.inquireCcnl(DEFAULT_EXCHANGE, symbol, LocalDate.now());
        if (ccnl != null && "0".equals(ccnl.resultCode()) && ccnl.output() != null) {
            Optional<KisOverseasCcnlResponse.Item> hit = ccnl.output().stream()
                    .filter(i -> symbol.equals(i.pdno()))
                    .filter(i -> sideCode.equals(i.sideCode()))
                    .findFirst();

            if (hit.isPresent()) {
                return OrderInquiryResult.found(hit.get().orderNo(), "주문체결내역 조회됨");
            }
        }

        return OrderInquiryResult.notFound("주문체결내역이 없음");
    }
}
