package my.side.trading.core.application.portfolio;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PortfolioPerformanceSnapshotService {

    private final PortfolioService portfolioService;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;

    public PortfolioSnapshot captureDailySnapshot(LocalDate asOfDate) {
        Portfolio portfolio = portfolioService.getCurrentPortfolio();
        BigDecimal totalValue = portfolio.totalValue();
        BigDecimal peakNav = historicalPeakBefore(asOfDate, totalValue);

        PortfolioSnapshot snapshot = new PortfolioSnapshot(
                asOfDate,
                totalValue,
                portfolio.cash(),
                portfolio.wQqq(),
                portfolio.wQld(),
                portfolio.wTqqq(),
                drawdownPct(peakNav, totalValue)
        );

        return portfolioSnapshotRepository.save(snapshot);
    }

    private BigDecimal historicalPeakBefore(LocalDate asOfDate, BigDecimal currentNav) {
        return portfolioSnapshotRepository.findAllOrderByAsOfDateAsc().stream()
                .filter(snapshot -> snapshot.asOfDate().isBefore(asOfDate))
                .map(PortfolioSnapshot::totalValue)
                .max(Comparator.naturalOrder())
                .map(peak -> peak.max(currentNav))
                .orElse(currentNav);
    }

    static BigDecimal drawdownPct(BigDecimal peakNav, BigDecimal currentNav) {
        if (peakNav == null || currentNav == null || peakNav.signum() <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        return peakNav.subtract(currentNav)
                .divide(peakNav, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
