package my.side.trading.core.domain.snapshot;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PortfolioSnapshot(
        LocalDate asOfDate,        // 기준 일자 (EOD)
        BigDecimal totalValue,     // 총자산
        BigDecimal cash,           // 현금
        BigDecimal wQqq,           // QQQ 비중 (0~1 or 퍼센트, 하나로 통일)
        BigDecimal wQld,
        BigDecimal wTqqq,
        BigDecimal ddPercent,      // DD 퍼센트 (예: 15.23)
        int strategyVersion        // 그날 적용된 전략 버전
) {}
