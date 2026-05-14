package my.side.trading.adapter.out.kis.account;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisOverseasPeriodProfitService;
import my.side.trading.adapter.out.kis.dto.KisOverseasPeriodProfitResponse;
import my.side.trading.core.application.port.out.BrokerDailyPerformanceReader;
import my.side.trading.shared.security.SensitiveDataSanitizer;
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
            if (response == null) {
                return List.of();
            }
            if (!"0".equals(response.resultCode())) {
                log.warn("KIS 해외 기간 손익 조회가 실패했습니다: rt_cd={}, msg_cd={}, msg={}",
                        response.resultCode(),
                        response.messageCode(),
                        SensitiveDataSanitizer.sanitize(response.message()));
                return List.of();
            }
            if (response.items().isEmpty()) {
                return List.of();
            }

            Map<LocalDate, List<KisOverseasPeriodProfitResponse.Item>> byDate = response.items().stream()
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
                            BigDecimal.ZERO,
                            resolveFxRate(entry.getKey(), entry.getValue())
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("KIS 해외 기간 손익 조회 중 예외가 발생했습니다: startDate={}, endDate={}, reason={}",
                    startDate, endDate, SensitiveDataSanitizer.sanitizeThrowable(e));
            return List.of();
        }
    }

    private BigDecimal toDecimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.replace(",", ""));
    }

    private BigDecimal resolveFxRate(LocalDate date, List<KisOverseasPeriodProfitResponse.Item> items) {
        List<BigDecimal> distinctRates = items.stream()
                .map(KisOverseasPeriodProfitResponse.Item::firstNoticeExchangeRate)
                .map(this::toDecimal)
                .filter(rate -> rate.signum() > 0)
                .distinct()
                .toList();
        if (distinctRates.size() > 1) {
            log.warn("KIS 해외 기간 손익 응답에 일자별 first notice exchange rate가 여러 개 있습니다: date={}, rates={}",
                    date,
                    distinctRates);
        }
        return distinctRates.isEmpty() ? BigDecimal.ZERO : distinctRates.getFirst();
    }
}
