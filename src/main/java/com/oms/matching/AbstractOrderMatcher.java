package com.oms.matching;

import com.oms.book.OrderBook;
import com.oms.marketdata.MarketPriceTracker;
import com.oms.model.MatchResult;
import com.oms.model.Order;
import com.oms.model.OrderStatus;
import com.oms.model.Side;
import com.oms.model.Trade;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared continuous price-time-priority fill loop for {@link OrderMatcher} implementations.
 *
 * <p>The loop repeatedly takes the best resting order on the opposite side and fills against it
 * while {@link #crosses(Order, Order)} holds. Executions are normally priced at the resting (maker)
 * order's price; when the maker is a market order the incoming order supplies the price, and when
 * both are market orders the symbol's tracked {@link MarketPriceTracker market price} is used.
 * Every execution updates that tracked price. Subclasses supply the behaviour that differs by order
 * type: whether an incoming order {@link #crosses(Order, Order) crosses} a resting order.
 */
abstract class AbstractOrderMatcher implements OrderMatcher {

    private final Clock clock;
    private final MarketPriceTracker marketPriceTracker;

    protected AbstractOrderMatcher(Clock clock, MarketPriceTracker marketPriceTracker) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.marketPriceTracker = Objects.requireNonNull(marketPriceTracker, "marketPriceTracker must not be null");
    }

    @Override
    public final MatchResult match(Order incoming, OrderBook book) {
        Objects.requireNonNull(incoming, "incoming order must not be null");
        Objects.requireNonNull(book, "book must not be null");

        if (!canExecute(incoming, book)) {
            // Pre-trade check failed: no fills at all, hand the full quantity to the remainder
            // policy (e.g. fill-or-kill cancels the entire order).
            return handleRemainder(incoming, incoming.quantity(), List.of(), book);
        }

        List<Trade> trades = new ArrayList<>();
        BigDecimal remaining = incoming.quantity();

        while (remaining.signum() > 0) {
            Optional<Order> bestOpposite = bestOpposite(incoming.side(), book);
            if (bestOpposite.isEmpty() || !crosses(incoming, bestOpposite.get())) {
                break;
            }

            Order resting = bestOpposite.get();
            BigDecimal fillQty = remaining.min(resting.quantity());
            trades.add(execute(incoming, resting, fillQty));

            BigDecimal restingRemaining = resting.quantity().subtract(fillQty);
            if (restingRemaining.signum() == 0) {
                book.removeOrder(resting);
            } else {
                // Reduce the resting order in place; its timestamp is preserved, so it keeps its
                // time priority for subsequent matches.
                book.updateOrder(resting.id(), b -> b.quantity(restingRemaining));
            }

            remaining = remaining.subtract(fillQty);
        }

        if (remaining.signum() == 0) {
            return new MatchResult(trades, Optional.empty());
        }
        return handleRemainder(incoming, remaining, trades, book);
    }

    /**
     * Pre-trade check run before any fill occurs. If it fails, the fill loop is skipped entirely
     * and the whole quantity goes straight to {@link #handleRemainder}, guaranteeing the book is
     * untouched. The default accepts every order; an all-or-nothing matcher overrides this to
     * demand sufficient executable liquidity up front.
     *
     * @return whether the incoming order is allowed to begin filling
     */
    protected boolean canExecute(Order incoming, OrderBook book) {
        return true;
    }

    /**
     * @return whether the incoming order may execute against the given resting order
     */
    protected abstract boolean crosses(Order incoming, Order resting);

    /**
     * Disposes of the incoming order's unfilled quantity once the book can no longer fill it. The
     * default rests the remainder on the book (where a market order ranks ahead of limit orders);
     * subtypes may override, for example to cancel the remainder instead.
     *
     * @param incoming  the original incoming order
     * @param remaining the strictly-positive unfilled quantity
     * @param trades    the executions produced so far
     * @param book      the book being matched against
     * @return the final match result
     */
    protected MatchResult handleRemainder(
            Order incoming, BigDecimal remaining, List<Trade> trades, OrderBook book) {
        Order rested = incoming.toBuilder()
                .quantity(remaining)
                .status(OrderStatus.PENDING)
                .build();
        book.addOrder(rested);
        return new MatchResult(trades, Optional.of(rested));
    }

    /**
     * @return the current market (last-traded) price for the symbol, if any; used by subtypes to
     *         decide whether two market orders can be priced against each other
     */
    protected final Optional<BigDecimal> marketPrice(String symbol) {
        return marketPriceTracker.lastPrice(symbol);
    }

    private Optional<Order> bestOpposite(Side incomingSide, OrderBook book) {
        return incomingSide == Side.BUY ? book.bestSell() : book.bestBuy();
    }

    private Trade execute(Order incoming, Order resting, BigDecimal quantity) {
        boolean incomingIsBuy = incoming.side() == Side.BUY;
        BigDecimal tradePrice = priceFor(incoming, resting);
        Trade trade = new Trade(
                incomingIsBuy ? incoming.id() : resting.id(),
                incomingIsBuy ? resting.id() : incoming.id(),
                incoming.symbol(),
                tradePrice,
                quantity,
                clock.instant()
        );
        // Every execution updates the symbol's market price, keeping it at the last-traded price.
        marketPriceTracker.record(incoming.symbol(), tradePrice);
        return trade;
    }

    /**
     * Determines the execution price: the resting (maker) order's price; or, if the maker is a
     * market order, the incoming order's price; or, if both are market orders, the symbol's tracked
     * market price. The last case is only reached once {@link #crosses(Order, Order)} has confirmed
     * a market price exists.
     */
    private BigDecimal priceFor(Order incoming, Order resting) {
        if (!resting.isMarket()) {
            return resting.price();
        }
        if (!incoming.isMarket()) {
            return incoming.price();
        }
        return marketPriceTracker.lastPrice(incoming.symbol())
                .orElseThrow(() -> new IllegalStateException(
                        "no market price available to match two market orders for " + incoming.symbol()));
    }
}
