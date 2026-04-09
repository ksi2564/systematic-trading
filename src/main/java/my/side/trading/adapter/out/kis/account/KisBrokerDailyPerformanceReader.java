package my.side.trading.adapter.out.kis.account;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisOverseasPeriodProfitService;
import my.side.trading.adapter.out.kis.dto.KisOverseasPeriodProfitResponse;
import my.side.trading.core.application.port.out.BrokerDailyPerformanceReader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisBrokerDailyPerformanceReader implements BrokerDailyPerformanceReader {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisOverseasPeriodProfitService periodProfitService;

    @Override
    public List<BrokerDailyPerformance> readDailyPerformances(LocalDate startDate, LocalDate endDate) {
        try {
            KisOverseasPeriodProfitResponse response = periodProfitService.getPeriodProfit(startDate, endDate);
            if (response == null || response.output1() == null || response.output1().isEmpty()) {
                return List.of();
            }
            if (!"0".equals(response.resultCode())) {
                log.warn("KIS overseas period profit failed: rt_cd={}, msg_cd={}, msg={}",
                        response.resultCode(),
                        response.messageCode(),
                        response.message());
                return List.of();
            }

            Map<LocalDate, List<KisOverseasPeriodProfitResponse.Item>> byDate = response.output1().stream()
                    .filter(item -> item.tradeDate() != null && !item.tradeDate().isBlank())
                    .collect(Collectors.groupingBy(
                            item -> LocalDate.parse(item.tradeDate(), YYYYMMDD),
                            Collectors.toList()
                    ));

            return byDate.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new BrokerDailyPerformance(
                            entry.getKey(),
                            entry.getValue().stream().map(item -> toDecimal(item.realizedPnl())).reduce(BigDecimal.ZERO, BigDecimal::add),
                            entry.getValue().stream().map(item -> toDecimal(item.fee())).reduce(BigDecimal.ZERO, BigDecimal::add),
                            entry.getValue().stream().map(item -> toDecimal(item.tax())).reduce(BigDecimal.ZERO, BigDecimal::add)
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("KIS overseas period profit read failed: startDate={}, endDate={}, reason={}",
                    startDate, endDate, e.toString());
            return List.of();
        }
    }

    private BigDecimal toDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.replace(",", ""));
    }
}
