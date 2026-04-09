package my.side.trading.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "trading.performance")
public record TradingPerformanceProps(
        BackfillProps backfill,
        HoldingCostProps holdingCost,
        FxProps fx
) {
    public TradingPerformanceProps {
        backfill = backfill == null ? new BackfillProps(365) : backfill;
        holdingCost = holdingCost == null ? new HoldingCostProps(new HoldingCostSymbolProps(null, null, null)) : holdingCost;
        fx = fx == null ? new FxProps("USDKRW") : fx;
    }

    public record BackfillProps(
            int defaultDays
    ) {
        public BackfillProps {
            defaultDays = defaultDays <= 0 ? 365 : defaultDays;
        }
    }

    public record HoldingCostProps(
            HoldingCostSymbolProps symbols
    ) {
        public HoldingCostProps {
            symbols = symbols == null ? new HoldingCostSymbolProps(null, null, null) : symbols;
        }
    }

    public record HoldingCostSymbolProps(
            BigDecimal QQQ,
            BigDecimal QLD,
            BigDecimal TQQQ
    ) {
        public HoldingCostSymbolProps {
            QQQ = normalize(QQQ);
            QLD = normalize(QLD);
            TQQQ = normalize(TQQQ);
        }

        private static BigDecimal normalize(BigDecimal value) {
            if (value == null || value.signum() < 0) {
                return BigDecimal.ZERO;
            }
            return value;
        }
    }

    public record FxProps(
            String usdKrwSymbol
    ) {
        public FxProps {
            usdKrwSymbol = usdKrwSymbol == null || usdKrwSymbol.isBlank() ? "USDKRW" : usdKrwSymbol;
        }
    }
}
