package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.portfolio.RealtimePriceProvider;
import my.side.trading.core.domain.portfolio.RealtimeQuote;
import my.side.trading.core.domain.strategy.WeightSet;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 목표비중 기반 주문 생성 팩토리
 * 주문 시점의 실시간 가격으로 수량 계산
 */
@Component
@RequiredArgsConstructor
public class ExecutionOrderFactory {

    private static final BigDecimal SELL_PROCEEDS_HAIRCUT = new BigDecimal("0.995");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final RealtimePriceProvider priceProvider;
    private final MarketLikePricingPolicy pricingPolicy;

    /**
     * 목표비중과 현재 포트폴리오를 기반으로 주문 생성
     *
     * @param symbol           종목
     * @param side             매수/매도
     * @param targetWeights    목표 비중
     * @param portfolio        현재 포트폴리오
     * @param remainingCashUsd 사용 가능한 현금 (매수 시)
     * @return 생성된 주문과 예상 현금 변동
     */
    public Optional<OrderAndCashDelta> fromTargetWeight(
            String symbol,
            ExecutionOrderSide side,
            WeightSet targetWeights,
            Portfolio portfolio,
            BigDecimal remainingCashUsd) {
        RealtimeQuote quote = priceProvider.getQuote(symbol)
                .orElseThrow(() -> new MarketQuoteUnavailableException(symbol));
        BigDecimal refPrice = resolveReferencePrice(side, quote);
        BigDecimal limitPrice = computeLimitPrice(side, refPrice, 1);
        BigDecimal totalValue = portfolio.totalValue();

        // 목표 금액과 현재 보유 금액 계산
        BigDecimal targetPct = getWeightForSymbol(targetWeights, symbol);
        BigDecimal targetNotional = totalValue.multiply(targetPct).divide(HUNDRED, 8, RoundingMode.HALF_UP);
        BigDecimal currentNotional = getCurrentNotional(portfolio, symbol);

        BigDecimal diffNotional = targetNotional.subtract(currentNotional).abs();

        long desiredQty = diffNotional.divide(limitPrice, 0, RoundingMode.DOWN).longValue();
        if (desiredQty <= 0)
            return Optional.empty();

        long finalQty = desiredQty;

        if (side == ExecutionOrderSide.SELL) {
            long maxSellQty = portfolio.quantityOf(symbol)
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
            finalQty = Math.min(desiredQty, maxSellQty);

        } else if (side == ExecutionOrderSide.BUY) {
            finalQty = Math.min(desiredQty, computeMaxBuyQty(remainingCashUsd, limitPrice));
        }

        if (finalQty <= 0)
            return Optional.empty();

        ExecutionOrder order = createOrder(symbol, side, finalQty, quote, 1);

        BigDecimal cashDelta = estimateCashDelta(order);
        return Optional.of(new OrderAndCashDelta(order, cashDelta));
    }

    public ExecutionOrder repriceForRetry(ExecutionOrder original, long newQty, int attempt) {
        RealtimeQuote quote = priceProvider.getQuote(original.getSymbol())
                .orElseThrow(() -> new MarketQuoteUnavailableException(original.getSymbol()));
        BigDecimal refPrice = resolveReferencePrice(original.getSide(), quote);
        BigDecimal limitPrice = computeLimitPrice(original.getSide(), refPrice, attempt);
        return ExecutionOrder.rehydrate(
                original.getId(),
                original.getSymbol(),
                original.getSide(),
                newQty,
                refPrice,
                limitPrice,
                original.getStatus(),
                null,
                null,
                original.getRequestedMarketAt());
    }

    private BigDecimal getWeightForSymbol(WeightSet weights, String symbol) {
        return switch (symbol) {
            case "QQQ" -> weights.wQqq();
            case "QLD" -> weights.wQld();
            case "TQQQ" -> weights.wTqqq();
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal getCurrentNotional(Portfolio portfolio, String symbol) {
        return portfolio.positions().stream()
                .filter(p -> p.symbol().equals(symbol))
                .map(Position::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ExecutionOrder createOrder(
            String symbol,
            ExecutionOrderSide side,
            long quantity,
            RealtimeQuote quote,
            int attempt) {
        BigDecimal refPrice = resolveReferencePrice(side, quote);
        BigDecimal limitPrice = computeLimitPrice(side, refPrice, attempt);
        return ExecutionOrder.create(symbol, side, quantity, refPrice, limitPrice);
    }

    private BigDecimal resolveReferencePrice(ExecutionOrderSide side, RealtimeQuote quote) {
        BigDecimal preferred = side == ExecutionOrderSide.BUY ? quote.bestAskPrice() : quote.bestBidPrice();
        if (preferred != null && preferred.signum() > 0) {
            return preferred.setScale(2, RoundingMode.HALF_UP);
        }
        if (quote.lastPrice() != null && quote.lastPrice().signum() > 0) {
            return quote.lastPrice().setScale(2, RoundingMode.HALF_UP);
        }
        throw new IllegalStateException("주문 기준 가격을 계산할 수 없습니다.");
    }

    private BigDecimal computeLimitPrice(ExecutionOrderSide side, BigDecimal refPrice, int attempt) {
        int ticks = pricingPolicy.priceTicksForAttempt(side, attempt);
        BigDecimal adjustment = pricingPolicy.tickSize().multiply(BigDecimal.valueOf(ticks));
        BigDecimal limitPrice = side == ExecutionOrderSide.BUY
                ? refPrice.add(adjustment)
                : refPrice.subtract(adjustment);
        return limitPrice.setScale(2, RoundingMode.HALF_UP);
    }

    private long computeMaxBuyQty(BigDecimal remainingCashUsd, BigDecimal limitPrice) {
        if (remainingCashUsd == null || remainingCashUsd.signum() <= 0)
            return 0L;
        BigDecimal feeRate = pricingPolicy.feeRate();
        BigDecimal finalPrice = limitPrice.multiply(BigDecimal.ONE.add(feeRate));
        if (finalPrice.signum() <= 0)
            return 0L;
        return remainingCashUsd.divide(finalPrice, 0, RoundingMode.DOWN).longValue();
    }

    private BigDecimal estimateCashDelta(ExecutionOrder order) {
        BigDecimal feeRate = pricingPolicy.feeRate();
        BigDecimal notional = order.getLimitPrice().multiply(BigDecimal.valueOf(order.getQuantity()));

        if (order.getSide() == ExecutionOrderSide.BUY) {
            return notional.multiply(BigDecimal.ONE.add(feeRate)).negate();
        }

        if (order.getSide() == ExecutionOrderSide.SELL) {
            return notional
                    .multiply(BigDecimal.ONE.subtract(feeRate))
                    .multiply(SELL_PROCEEDS_HAIRCUT);
        }

        return BigDecimal.ZERO;
    }
}
