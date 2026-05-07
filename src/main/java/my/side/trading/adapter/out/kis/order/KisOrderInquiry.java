package my.side.trading.adapter.out.kis.order;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.client.KisOverseasCcnlService;
import my.side.trading.adapter.out.kis.client.KisOverseasNccsService;
import my.side.trading.adapter.out.kis.dto.KisOverseasCcnlResponse;
import my.side.trading.adapter.out.kis.dto.KisOverseasNccsResponse;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.OrderInquiry;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class KisOrderInquiry implements OrderInquiry {

    // TODO: 추후 order에 exchange를 넣거나 Portfolio/Strategy에서 주입
    private static final String DEFAULT_EXCHANGE = "NASD";
    private static final Duration REQUEST_TIME_WINDOW = Duration.ofMinutes(5);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private final KisOverseasNccsService nccsService;
    private final KisOverseasCcnlService ccnlService;
    private final MarketCalendarService marketCalendarService;

    @Override
    public OrderInquiryResult confirm(ExecutionOrder order) {
        try {
            String brokerOrderId = order.getBrokerOrderId();
            if (brokerOrderId != null && !brokerOrderId.isBlank()) {
                OrderInquiryResult result = confirmByBrokerOrderId(order, brokerOrderId);
                if (result.found()) {
                    return result;
                }
            }

            return confirmByOrderAttributes(order);
        } catch (Exception e) {
            return OrderInquiryResult.failed("KIS 주문 확인 조회 실패: " + e.getClass().getSimpleName());
        }
    }

    private OrderInquiryResult confirmByBrokerOrderId(ExecutionOrder order, String brokerOrderId) {
        KisOverseasCcnlResponse ccnl = ccnlService.inquireCcnl(
                DEFAULT_EXCHANGE,
                order.getSymbol(),
                inquiryDate(order),
                brokerOrderId);
        if (isSuccessful(ccnl) && ccnl.output() != null) {
            Optional<KisOverseasCcnlResponse.Item> hit = ccnl.output().stream()
                    .filter(i -> brokerOrderId.equals(i.orderNo()))
                    .findFirst();

            if (hit.isPresent()) {
                return OrderInquiryResult.found(hit.get().orderNo(), "주문체결내역에서 주문번호 조회됨");
            }
        }

        return OrderInquiryResult.notFound("주문번호와 일치하는 주문체결내역이 없음");
    }

    private OrderInquiryResult confirmByOrderAttributes(ExecutionOrder order) {
        String symbol = order.getSymbol();
        String sideCode = sideCode(order);

        KisOverseasNccsResponse nccs = nccsService.inquireNccs(DEFAULT_EXCHANGE);
        if (isSuccessful(nccs) && nccs.output() != null) {
            Optional<KisOverseasNccsResponse.Item> hit = nccs.output().stream()
                    .filter(i -> symbol.equals(i.pdno()))
                    .filter(i -> sideCode.equals(i.sideCode()))
                    .filter(i -> sameQuantity(order.getQuantity(), i.orderQty()))
                    .filter(i -> matchesRequestWindow(order, i.orderDate(), i.orderTime()))
                    .findFirst();

            if (hit.isPresent()) {
                return OrderInquiryResult.found(hit.get().orderNo(), "미체결내역에서 주문 후보 조회됨");
            }
        }

        KisOverseasCcnlResponse ccnl = ccnlService.inquireCcnl(DEFAULT_EXCHANGE, symbol, inquiryDate(order));
        if (isSuccessful(ccnl) && ccnl.output() != null) {
            Optional<KisOverseasCcnlResponse.Item> hit = ccnl.output().stream()
                    .filter(i -> symbol.equals(i.pdno()))
                    .filter(i -> sideCode.equals(i.sideCode()))
                    .filter(i -> sameQuantity(order.getQuantity(), i.orderQty()))
                    .filter(i -> matchesRequestWindow(order, i.orderDate(), i.orderTime()))
                    .findFirst();

            if (hit.isPresent()) {
                return OrderInquiryResult.found(hit.get().orderNo(), "주문체결내역에서 주문 후보 조회됨");
            }
        }

        return OrderInquiryResult.notFound("symbol/side/quantity와 일치하는 주문 확인 내역이 없음");
    }

    private String sideCode(ExecutionOrder order) {
        return (order.getSide() == ExecutionOrderSide.BUY) ? "02" : "01";
    }

    private LocalDate inquiryDate(ExecutionOrder order) {
        return order.getRequestedMarketAt() == null
                ? marketCalendarService.currentMarketDate()
                : order.getRequestedMarketAt().toLocalDate();
    }

    private boolean isSuccessful(KisOverseasNccsResponse response) {
        return response != null && "0".equals(response.resultCode());
    }

    private boolean isSuccessful(KisOverseasCcnlResponse response) {
        return response != null && "0".equals(response.resultCode());
    }

    private boolean sameQuantity(long expected, String actual) {
        if (actual == null || actual.isBlank()) {
            return false;
        }
        try {
            return BigDecimal.valueOf(expected).compareTo(new BigDecimal(actual.trim())) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean matchesRequestWindow(ExecutionOrder order, String orderDate, String orderTime) {
        LocalDateTime requestedMarketAt = order.getRequestedMarketAt();
        if (requestedMarketAt == null) {
            return true;
        }
        Optional<LocalDateTime> brokerRequestedAt = parseBrokerOrderTime(orderDate, orderTime);
        return brokerRequestedAt
                .map(value -> !value.isBefore(requestedMarketAt.minus(REQUEST_TIME_WINDOW))
                        && !value.isAfter(requestedMarketAt.plus(REQUEST_TIME_WINDOW)))
                .orElse(false);
    }

    private Optional<LocalDateTime> parseBrokerOrderTime(String orderDate, String orderTime) {
        if (orderDate == null || orderDate.isBlank() || orderTime == null || orderTime.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.of(
                    LocalDate.parse(orderDate.trim(), YYYYMMDD),
                    LocalTime.parse(orderTime.trim(), HHMMSS)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
