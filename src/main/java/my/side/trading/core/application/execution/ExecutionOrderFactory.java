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

    private static final BigDecimal SELL_PROCEEDS_HAIRCUT = new BigDecimal("0.995");

    private final RealtimePriceProvider priceProvider;
    private final MarketLikePricingPolicy pricingPolicy;

    public Optional<OrderAndCashDelta> fromIntentWithCashDelta(
            OrderIntent intent,
            Portfolio portfolio,
            BigDecimal remainingCashUsd
    ) {
        BigDecimal refPrice = priceProvider.getLastPrice(intent.symbol())
                .orElseThrow(() -> new IllegalStateException("price cache miss: " + intent.symbol()));

        BigDecimal limitPrice = computeLimitPrice(intent.side(), refPrice);

        long desiredQty = computeDesiredQty(intent.notionalUsd(), limitPrice);
        if (desiredQty <= 0) return Optional.empty();

        long finalQty = desiredQty;

        if (intent.side() == ExecutionOrderSide.SELL) {
            long maxSellQty = portfolio.quantityOf(intent.symbol())
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
            finalQty = Math.min(desiredQty, maxSellQty);

        } else if (intent.side() == ExecutionOrderSide.BUY) {
            finalQty = Math.min(desiredQty, computeMaxBuyQty(remainingCashUsd, limitPrice));
        }

        if (finalQty <= 0) return Optional.empty();

        ExecutionOrder order = ExecutionOrder.create(
                intent.symbol(),
                intent.side(),
                finalQty,
                refPrice,
                limitPrice
        );

        BigDecimal cashDelta = estimateCashDelta(order);

        return Optional.of(new OrderAndCashDelta(order, cashDelta));
    }

    private BigDecimal computeLimitPrice(ExecutionOrderSide side, BigDecimal refPrice) {
        BigDecimal multiplier = (side == ExecutionOrderSide.BUY)
                ? BigDecimal.ONE.add(pricingPolicy.buyBuffer())
                : BigDecimal.ONE.subtract(pricingPolicy.sellBuffer());

        return refPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    private long computeDesiredQty(BigDecimal notionalUsd, BigDecimal finalPrice) {
        if (notionalUsd == null || notionalUsd.signum() <= 0) return 0L;
        return notionalUsd
                .divide(finalPrice, 0, RoundingMode.DOWN)
                .longValueExact();
    }

    /**
     * BUY 최대 가능 수량 = remainingCash / (limitPrice * (1 + feeRate))
     */
    private long computeMaxBuyQty(BigDecimal remainingCashUsd, BigDecimal limitPrice) {
        if (remainingCashUsd == null || remainingCashUsd.signum() <= 0) return 0L;

        BigDecimal feeRate = pricingPolicy.feeRate();
        BigDecimal finalPrice = limitPrice.multiply(BigDecimal.ONE.add(feeRate));
        if (finalPrice.signum() <= 0) return 0L;

        return remainingCashUsd.divide(finalPrice, 0, RoundingMode.DOWN).longValue();
    }

    /**
     * cashDelta 정의:
     * - BUY: - (limitPrice * qty * (1 + feeRate))
     * - SELL: + (limitPrice * qty * (1 - feeRate) * haircut)
     */
    private BigDecimal estimateCashDelta(ExecutionOrder order) {
        BigDecimal feeRate = pricingPolicy.feeRate();
        BigDecimal notional = order.getLimitPrice().multiply(BigDecimal.valueOf(order.getQuantity()));

        if (order.getSide() == ExecutionOrderSide.BUY) {
            BigDecimal cashOut = notional.multiply(BigDecimal.ONE.add(feeRate));
            return cashOut.negate();
        }

        if (order.getSide() == ExecutionOrderSide.SELL) {
            BigDecimal cashIn = notional
                    .multiply(BigDecimal.ONE.subtract(feeRate))
                    .multiply(SELL_PROCEEDS_HAIRCUT);
            return cashIn;
        }

        return BigDecimal.ZERO;
    }
}
