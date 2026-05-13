package my.side.trading.core.domain.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PortfolioSnapshot(
        LocalDate asOfDate,        // 기준 일자 (EOD)
        BigDecimal totalValue,     // 총자산
        BigDecimal cash,           // 현금
        BigDecimal wBase,          // 기본 ETF 비중(%)
        BigDecimal wQld,           // QLD 비중(%)
        BigDecimal wTqqq,          // TQQQ 비중(%)
        BigDecimal ddPercent       // DD 퍼센트 (예: 15.23)
) {
    public BigDecimal wQqq() {
        return wBase;
    }
}
