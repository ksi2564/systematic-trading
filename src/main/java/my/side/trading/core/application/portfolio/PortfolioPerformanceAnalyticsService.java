package my.side.trading.core.application.portfolio;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.port.out.BrokerDailyPerformanceReader;
import my.side.trading.core.application.port.out.FxRateReader;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshotRepository;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshotRepository;
import my.side.trading.core.infrastructure.config.TradingPerformanceProps;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PortfolioPerformanceAnalyticsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final int MAX_MISSING_DATES = 30;

    private final PortfolioPerformanceService portfolioPerformanceService;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final PerformanceAnalyticsSnapshotRepository performanceAnalyticsSnapshotRepository;
    private final BrokerDailyPerformanceReader brokerDailyPerformanceReader;
    private final FxRateReader fxRateReader;
    private final TradingPerformanceProps performanceProps;

    public PerformanceAnalyticsSnapshot captureDailyAnalytics(LocalDate asOfDate) {
        PortfolioSnapshot portfolioSnapshot = portfolioSnapshotRepository.findByAsOfDate(asOfDate)
                .orElseThrow(() -> new IllegalArgumentException("Portfolio snapshot not found for " + asOfDate));

        BrokerDailyPerformanceReader.BrokerDailyPerformance brokerPerformance = brokerDailyPerformanceReader
                .readDailyPerformances(asOfDate, asOfDate)
                .stream()
                .filter(item -> item.date().isEqual(asOfDate))
                .findFirst()
                .orElse(null);
        BigDecimal valuationFxRate = defaultScale(resolveValuationFxRate(asOfDate, brokerPerformance), 8);
        BigDecimal actualConversionFxRate = defaultScale(resolveActualConversionFxRate(brokerPerformance, valuationFxRate), 8);

        BigDecimal navUsd = defaultScale(portfolioSnapshot.totalValue(), 4);
        BigDecimal navKrw = multiply(navUsd, valuationFxRate, 4);
        BigDecimal realizedPnlUsd = defaultScale(brokerPerformance == null ? null : brokerPerformance.realizedPnlUsd(), 4);
        BigDecimal brokerFeeUsd = defaultScale(brokerPerformance == null ? null : brokerPerformance.brokerFeeUsd(), 4);
        BigDecimal taxUsd = defaultScale(brokerPerformance == null ? null : brokerPerformance.taxUsd(), 4);
        BigDecimal realizedPnlKrw = multiply(realizedPnlUsd, actualConversionFxRate, 4);
        BigDecimal brokerFeeKrw = multiply(brokerFeeUsd, actualConversionFxRate, 4);
        BigDecimal taxKrw = multiply(taxUsd, actualConversionFxRate, 4);
        boolean holdingCostConfigured = isHoldingCostConfigured();
        BigDecimal holdingCostEstimateUsd = defaultScale(calculateHoldingCostUsd(portfolioSnapshot), 4);
        BigDecimal holdingCostEstimateKrw = multiply(holdingCostEstimateUsd, valuationFxRate, 4);
        boolean actualDataReady = brokerPerformance != null && actualConversionFxRate.signum() > 0;

        return performanceAnalyticsSnapshotRepository.save(new PerformanceAnalyticsSnapshot(
                asOfDate,
                navUsd,
                navKrw,
                valuationFxRate,
                realizedPnlUsd,
                realizedPnlKrw,
                brokerFeeUsd,
                brokerFeeKrw,
                taxUsd,
                taxKrw,
                actualDataReady,
                holdingCostEstimateUsd,
                holdingCostEstimateKrw,
                holdingCostConfigured
        ));
    }

    public PortfolioPerformanceAnalyticsSummary getSummary() {
        PortfolioPerformanceSummary baseSummary = portfolioPerformanceService.getSummary();
        List<PortfolioSnapshot> portfolioSnapshots = portfolioSnapshotRepository.findAllOrderByAsOfDateAsc();
        List<PerformanceAnalyticsSnapshot> analyticsSnapshots = performanceAnalyticsSnapshotRepository.findAllOrderByAsOfDateAsc();
        return buildSummary(baseSummary, portfolioSnapshots, analyticsSnapshots);
    }

    public PortfolioPerformanceAnalyticsReport getReport(int dailyLimit) {
        PortfolioPerformanceReport baseReport = portfolioPerformanceService.getReport(dailyLimit);
        List<PortfolioSnapshot> portfolioSnapshots = portfolioSnapshotRepository.findAllOrderByAsOfDateAsc();
        List<PerformanceAnalyticsSnapshot> analyticsSnapshots = performanceAnalyticsSnapshotRepository.findAllOrderByAsOfDateAsc();
        PortfolioPerformanceAnalyticsSummary summary = buildSummary(baseReport.summary(), portfolioSnapshots, analyticsSnapshots);

        List<PerformanceAnalyticsSnapshot> recentDailyActualSnapshots = limitRecentSnapshots(analyticsSnapshots, baseReport.dailySnapshotLimit());
        List<PortfolioPerformanceAnalyticsReport.ActualMonthlyAnalytics> monthlyActualAnalytics = buildMonthlyActualAnalytics(analyticsSnapshots);

        return new PortfolioPerformanceAnalyticsReport(
                summary,
                baseReport.dailySnapshotLimit(),
                baseReport.recentDailySnapshots(),
                baseReport.monthlyPnls(),
                recentDailyActualSnapshots,
                monthlyActualAnalytics
        );
    }

    public List<PerformanceAnalyticsSnapshot> getRecentSnapshots(int limit) {
        return limitRecentSnapshots(performanceAnalyticsSnapshotRepository.findAllOrderByAsOfDateAsc(), limit);
    }

    private PortfolioPerformanceAnalyticsSummary buildSummary(
            PortfolioPerformanceSummary baseSummary,
            List<PortfolioSnapshot> portfolioSnapshots,
            List<PerformanceAnalyticsSnapshot> analyticsSnapshots
    ) {
        List<PerformanceAnalyticsSnapshot> readySnapshots = analyticsSnapshots.stream()
                .filter(PerformanceAnalyticsSnapshot::actualDataReady)
                .toList();

        BigDecimal totalRealizedUsd = readySnapshots.stream()
                .map(PerformanceAnalyticsSnapshot::realizedPnlUsd)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalRealizedKrw = readySnapshots.stream()
                .map(PerformanceAnalyticsSnapshot::realizedPnlKrw)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalBrokerFeeUsd = readySnapshots.stream()
                .map(PerformanceAnalyticsSnapshot::brokerFeeUsd)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalBrokerFeeKrw = readySnapshots.stream()
                .map(PerformanceAnalyticsSnapshot::brokerFeeKrw)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalTaxUsd = readySnapshots.stream()
                .map(PerformanceAnalyticsSnapshot::taxUsd)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalTaxKrw = readySnapshots.stream()
                .map(PerformanceAnalyticsSnapshot::taxKrw)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalHoldingCostUsd = analyticsSnapshots.stream()
                .map(PerformanceAnalyticsSnapshot::holdingCostEstimateUsd)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalHoldingCostKrw = analyticsSnapshots.stream()
                .map(PerformanceAnalyticsSnapshot::holdingCostEstimateKrw)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal netActualPnlUsd = totalRealizedUsd.subtract(totalBrokerFeeUsd).subtract(totalTaxUsd).setScale(4, RoundingMode.HALF_UP);
        BigDecimal netActualPnlKrw = totalRealizedKrw.subtract(totalBrokerFeeKrw).subtract(totalTaxKrw).setScale(4, RoundingMode.HALF_UP);

        Optional<PerformanceAnalyticsSnapshot> latestSnapshot = analyticsSnapshots.isEmpty()
                ? Optional.empty()
                : Optional.of(analyticsSnapshots.getLast());
        BigDecimal baseNavUsd = analyticsSnapshots.isEmpty() ? ZERO : defaultScale(analyticsSnapshots.getFirst().navUsd(), 4);
        BigDecimal baseNavKrw = analyticsSnapshots.isEmpty() ? ZERO : defaultScale(analyticsSnapshots.getFirst().navKrw(), 4);

        return new PortfolioPerformanceAnalyticsSummary(
                baseSummary,
                new PortfolioPerformanceAnalyticsSummary.ActualPerformanceSummary(
                        latestSnapshot.map(PerformanceAnalyticsSnapshot::asOfDate).orElse(null),
                        latestSnapshot.map(PerformanceAnalyticsSnapshot::navUsd).orElse(null),
                        netActualPnlUsd,
                        percentChange(baseNavUsd, netActualPnlUsd),
                        totalRealizedUsd
                ),
                new PortfolioPerformanceAnalyticsSummary.ActualPerformanceSummary(
                        latestSnapshot.map(PerformanceAnalyticsSnapshot::asOfDate).orElse(null),
                        latestSnapshot.map(PerformanceAnalyticsSnapshot::navKrw).orElse(null),
                        netActualPnlKrw,
                        percentChange(baseNavKrw, netActualPnlKrw),
                        totalRealizedKrw
                ),
                new PortfolioPerformanceAnalyticsSummary.CostBreakdown(
                        totalBrokerFeeUsd,
                        totalBrokerFeeKrw,
                        totalTaxUsd,
                        totalTaxKrw
                ),
                new PortfolioPerformanceAnalyticsSummary.HoldingCostEstimate(
                        analyticsSnapshots.stream().anyMatch(PerformanceAnalyticsSnapshot::holdingCostConfigured),
                        totalHoldingCostUsd,
                        totalHoldingCostKrw
                ),
                buildCoverage(portfolioSnapshots, analyticsSnapshots)
        );
    }

    private PortfolioPerformanceAnalyticsSummary.AnalysisCoverage buildCoverage(
            List<PortfolioSnapshot> portfolioSnapshots,
            List<PerformanceAnalyticsSnapshot> analyticsSnapshots
    ) {
        Map<LocalDate, PerformanceAnalyticsSnapshot> analyticsByDate = analyticsSnapshots.stream()
                .collect(Collectors.toMap(PerformanceAnalyticsSnapshot::asOfDate, Function.identity(), (left, right) -> right));
        List<LocalDate> missingDates = portfolioSnapshots.stream()
                .map(PortfolioSnapshot::asOfDate)
                .filter(date -> {
                    PerformanceAnalyticsSnapshot snapshot = analyticsByDate.get(date);
                    return snapshot == null || !snapshot.actualDataReady();
                })
                .limit(MAX_MISSING_DATES)
                .toList();
        int actualReadyCount = (int) analyticsSnapshots.stream()
                .filter(PerformanceAnalyticsSnapshot::actualDataReady)
                .count();
        String status;
        if (portfolioSnapshots.isEmpty()) {
            status = "UNAVAILABLE";
        } else if (actualReadyCount == portfolioSnapshots.size()) {
            status = "COMPLETE";
        } else if (actualReadyCount == 0) {
            status = "UNAVAILABLE";
        } else {
            status = "PARTIAL";
        }

        return new PortfolioPerformanceAnalyticsSummary.AnalysisCoverage(
                status,
                portfolioSnapshots.isEmpty() ? null : portfolioSnapshots.getFirst().asOfDate(),
                portfolioSnapshots.isEmpty() ? null : portfolioSnapshots.getLast().asOfDate(),
                portfolioSnapshots.size(),
                actualReadyCount,
                missingDates
        );
    }

    private List<PortfolioPerformanceAnalyticsReport.ActualMonthlyAnalytics> buildMonthlyActualAnalytics(
            List<PerformanceAnalyticsSnapshot> analyticsSnapshots
    ) {
        Map<YearMonth, List<PerformanceAnalyticsSnapshot>> byMonth = new LinkedHashMap<>();
        for (PerformanceAnalyticsSnapshot snapshot : analyticsSnapshots) {
            byMonth.computeIfAbsent(YearMonth.from(snapshot.asOfDate()), ignored -> new ArrayList<>())
                    .add(snapshot);
        }

        return byMonth.entrySet().stream()
                .map(entry -> {
                    List<PerformanceAnalyticsSnapshot> monthSnapshots = entry.getValue();
                    List<PerformanceAnalyticsSnapshot> readySnapshots = monthSnapshots.stream()
                            .filter(PerformanceAnalyticsSnapshot::actualDataReady)
                            .toList();
                    BigDecimal realizedUsd = readySnapshots.stream()
                            .map(PerformanceAnalyticsSnapshot::realizedPnlUsd)
                            .reduce(ZERO, BigDecimal::add);
                    BigDecimal realizedKrw = readySnapshots.stream()
                            .map(PerformanceAnalyticsSnapshot::realizedPnlKrw)
                            .reduce(ZERO, BigDecimal::add);
                    BigDecimal brokerFeeUsd = readySnapshots.stream()
                            .map(PerformanceAnalyticsSnapshot::brokerFeeUsd)
                            .reduce(ZERO, BigDecimal::add);
                    BigDecimal brokerFeeKrw = readySnapshots.stream()
                            .map(PerformanceAnalyticsSnapshot::brokerFeeKrw)
                            .reduce(ZERO, BigDecimal::add);
                    BigDecimal taxUsd = readySnapshots.stream()
                            .map(PerformanceAnalyticsSnapshot::taxUsd)
                            .reduce(ZERO, BigDecimal::add);
                    BigDecimal taxKrw = readySnapshots.stream()
                            .map(PerformanceAnalyticsSnapshot::taxKrw)
                            .reduce(ZERO, BigDecimal::add);
                    BigDecimal holdingCostEstimateUsd = monthSnapshots.stream()
                            .map(PerformanceAnalyticsSnapshot::holdingCostEstimateUsd)
                            .reduce(ZERO, BigDecimal::add);
                    BigDecimal holdingCostEstimateKrw = monthSnapshots.stream()
                            .map(PerformanceAnalyticsSnapshot::holdingCostEstimateKrw)
                            .reduce(ZERO, BigDecimal::add);

                    return new PortfolioPerformanceAnalyticsReport.ActualMonthlyAnalytics(
                            entry.getKey().toString(),
                            monthSnapshots.size(),
                            readySnapshots.size(),
                            realizedUsd.subtract(brokerFeeUsd).subtract(taxUsd).setScale(4, RoundingMode.HALF_UP),
                            realizedKrw.subtract(brokerFeeKrw).subtract(taxKrw).setScale(4, RoundingMode.HALF_UP),
                            realizedUsd,
                            realizedKrw,
                            brokerFeeUsd,
                            brokerFeeKrw,
                            taxUsd,
                            taxKrw,
                            holdingCostEstimateUsd,
                            holdingCostEstimateKrw
                    );
                })
                .toList();
    }

    private List<PerformanceAnalyticsSnapshot> limitRecentSnapshots(List<PerformanceAnalyticsSnapshot> snapshots, int limit) {
        if (snapshots.size() <= limit) {
            return snapshots;
        }
        return snapshots.subList(snapshots.size() - limit, snapshots.size());
    }

    private BigDecimal calculateHoldingCostUsd(PortfolioSnapshot snapshot) {
        TradingPerformanceProps.HoldingCostSymbolProps symbols = performanceProps.holdingCost().symbols();
        BigDecimal baseCost = estimateHoldingCost(snapshot.totalValue(), snapshot.wBase(), symbols.QQQM());
        BigDecimal qldCost = estimateHoldingCost(snapshot.totalValue(), snapshot.wQld(), symbols.QLD());
        BigDecimal tqqqCost = estimateHoldingCost(snapshot.totalValue(), snapshot.wTqqq(), symbols.TQQQ());
        return baseCost.add(qldCost).add(tqqqCost).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal estimateHoldingCost(BigDecimal navUsd, BigDecimal weightPct, BigDecimal annualCostPct) {
        if (navUsd == null || weightPct == null || annualCostPct == null
                || navUsd.signum() <= 0 || weightPct.signum() <= 0 || annualCostPct.signum() <= 0) {
            return ZERO;
        }

        return navUsd
                .multiply(weightPct)
                .divide(HUNDRED, 8, RoundingMode.HALF_UP)
                .multiply(annualCostPct)
                .divide(HUNDRED, 8, RoundingMode.HALF_UP)
                .divide(DAYS_PER_YEAR, 8, RoundingMode.HALF_UP)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private boolean isHoldingCostConfigured() {
        TradingPerformanceProps.HoldingCostSymbolProps symbols = performanceProps.holdingCost().symbols();
        return symbols.QQQM().signum() > 0 || symbols.QLD().signum() > 0 || symbols.TQQQ().signum() > 0;
    }

    private BigDecimal percentChange(BigDecimal baseValue, BigDecimal amount) {
        if (baseValue == null || amount == null || baseValue.signum() <= 0) {
            return ZERO;
        }
        return amount
                .divide(baseValue, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal multiply(BigDecimal left, BigDecimal right, int scale) {
        if (left == null || right == null || left.signum() == 0 || right.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return left.multiply(right).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal defaultScale(BigDecimal value, int scale) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveValuationFxRate(
            LocalDate asOfDate,
            BrokerDailyPerformanceReader.BrokerDailyPerformance brokerPerformance
    ) {
        BigDecimal rateFromFxReader = fxRateReader.readUsdKrwRates(asOfDate, asOfDate).get(asOfDate);
        if (rateFromFxReader != null && rateFromFxReader.signum() > 0) {
            return rateFromFxReader;
        }
        if (brokerPerformance != null && brokerPerformance.fxRate() != null && brokerPerformance.fxRate().signum() > 0) {
            return brokerPerformance.fxRate();
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveActualConversionFxRate(
            BrokerDailyPerformanceReader.BrokerDailyPerformance brokerPerformance,
            BigDecimal valuationFxRate
    ) {
        if (brokerPerformance != null && brokerPerformance.fxRate() != null && brokerPerformance.fxRate().signum() > 0) {
            return brokerPerformance.fxRate();
        }
        if (valuationFxRate != null && valuationFxRate.signum() > 0) {
            return valuationFxRate;
        }
        return BigDecimal.ZERO;
    }
}
