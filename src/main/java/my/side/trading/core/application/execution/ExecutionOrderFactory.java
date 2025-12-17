package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.RealtimePriceProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ExecutionOrderFactory {

    private final RealtimePriceProvider priceProvider;
    private final MarketLikePricingPolicy pricingPolicy =
            new MarketLikePricingPolicy(new BigDecimal("0.5"), new BigDecimal("0.5"));

    public Optional<ExecutionOrder> fromIntent(OrderIntent intent, Portfolio portfolio) {
        BigDecimal refPrice = priceProvider.getLastPrice(intent.symbol())
                .orElseThrow(() -> new IllegalStateException("price cache miss: " + intent.symbol()));

        BigDecimal limitPrice = computeLimitPrice(intent.side(), refPrice);

        long desireQty = computeDesiredQty(intent.notionalUsd(), limitPrice);
        if (desireQty <= 0) return Optional.empty();

        long finalQty = desireQty;
        if (intent.side() == ExecutionOrderSide.SELL) {
            long maxSellQty = portfolio.quantityOf(intent.symbol()).setScale(0, RoundingMode.DOWN).longValue();
            finalQty = Math.min(desireQty, Math.max(0, maxSellQty));
            if (finalQty <= 0) return Optional.empty();
        }

        return Optional.of(
                ExecutionOrder.create(
                        intent.symbol(),
                        intent.side(),
                        finalQty,
                        refPrice,
                        limitPrice
                )
        );
    }

    private long computeDesiredQty(BigDecimal notionalUsd, BigDecimal limitPrice) {
        if (notionalUsd == null || notionalUsd.signum() <= 0) return 0L;
        return notionalUsd
                .divide(limitPrice, 0, RoundingMode.DOWN)
                .longValueExact();
    }

    private BigDecimal computeLimitPrice(ExecutionOrderSide side, BigDecimal refPrice) {
        BigDecimal multiplier = (side == ExecutionOrderSide.BUY)
                ? BigDecimal.ONE.add(pricingPolicy.buyBuffer())
                : BigDecimal.ONE.subtract(pricingPolicy.sellBuffer());

        return refPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }
}
