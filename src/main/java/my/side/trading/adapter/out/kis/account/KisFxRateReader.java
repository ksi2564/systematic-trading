package my.side.trading.adapter.out.kis.account;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisDailyFxRateService;
import my.side.trading.adapter.out.kis.dto.KisDailyChartPriceResponse;
import my.side.trading.core.application.port.out.FxRateReader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisFxRateReader implements FxRateReader {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisDailyFxRateService dailyFxRateService;

    @Override
    public Map<LocalDate, BigDecimal> readUsdKrwRates(LocalDate startDate, LocalDate endDate) {
        try {
            KisDailyChartPriceResponse response = dailyFxRateService.getUsdKrwRates(startDate, endDate);
            if (response == null || response.items().isEmpty()) {
                return Map.of();
            }
            if (!"0".equals(response.resultCode())) {
                log.warn("KIS FX chart read failed: rt_cd={}, msg_cd={}, msg={}",
                        response.resultCode(),
                        response.messageCode(),
                        response.message());
                return Map.of();
            }

            Map<LocalDate, BigDecimal> rates = new LinkedHashMap<>();
            for (KisDailyChartPriceResponse.Item item : response.items()) {
                if (item.businessDate() == null || item.businessDate().isBlank()
                        || item.closePrice() == null || item.closePrice().isBlank()) {
                    continue;
                }
                rates.put(
                        LocalDate.parse(item.businessDate(), YYYYMMDD),
                        new BigDecimal(item.closePrice().replace(",", ""))
                );
            }
            return rates;
        } catch (Exception e) {
            log.warn("KIS FX chart read failed: startDate={}, endDate={}, reason={}",
                    startDate, endDate, e.toString());
            return Map.of();
        }
    }
}
