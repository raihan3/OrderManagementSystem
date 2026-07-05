package com.oms.book;

import com.oms.model.Order;
import com.oms.model.Side;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The order book for a single instrument: resting buy and sell orders held in matching-priority
 * order, plus dormant stop orders held in trigger-priority order.
 *
 * <p>The interface separates two kinds of capability:
 * <ul>
 *   <li><b>Primitives</b> — mutation ({@code add/remove/update}, stop parking and
 *       {@link #pollTriggeredStops(BigDecimal) triggering}) and the ordered views. How orders are
 *       stored and prioritised is the implementation's domain (see
 *       {@link PriceTimePriorityOrderBook}).</li>
 *   <li><b>Analytics</b> — {@link #spread()}, {@link #availableLiquidity(Side)},
 *       {@link #availableLiquidity(Side, BigDecimal)} and {@link #depth(int)} are pure derivations
 *       over the ordered views, provided here as default methods so every implementation answers
 *       them identically.</li>
 * </ul>
 *
 * <p>Implementations are not required to be thread-safe: a book is owned by a single matching
 * thread, per the engine's threading model.
 */
public interface OrderBook {

    /**
     * Adds an order to the buy or sell side of the book, based on its {@link Order#side()}.
     *
     * @throws IllegalArgumentException if the order is a stop order — dormant stops belong in the
     *                                  stop lists via {@link #addStopOrder(Order)}, not on the book
     */
    void addOrder(Order order);

    /**
     * Removes an order from the book.
     *
     * @return {@code true} if the order was present and removed
     */
    boolean removeOrder(Order order);

    /**
     * Amends the details of a resting order, keyed by its {@link Order#id()}. The amended order is
     * fully re-placed in the book, since the amendment may change its price, timestamp or side.
     *
     * @param id        id of the order to amend
     * @param amendment mutation applied to a builder pre-populated with the existing order
     * @return the amended order
     * @throws java.util.NoSuchElementException if no order with the given id is present
     */
    Order updateOrder(UUID id, UnaryOperator<Order.Builder> amendment);

    /**
     * @return the best resting buy order (a market order if one rests, otherwise the
     *         highest-priced limit), if any
     */
    Optional<Order> bestBuy();

    /**
     * @return the best resting sell order (a market order if one rests, otherwise the
     *         lowest-priced limit), if any
     */
    Optional<Order> bestSell();

    /**
     * @return a read-only, priority-ordered view of the resting buy orders, keyed by
     *         {@link Order#id()}
     */
    NavigableMap<UUID, Order> buyOrders();

    /**
     * @return a read-only, priority-ordered view of the resting sell orders, keyed by
     *         {@link Order#id()}
     */
    NavigableMap<UUID, Order> sellOrders();

    /**
     * Parks a dormant stop order in the stop list for its side until
     * {@link #pollTriggeredStops(BigDecimal)} releases it.
     *
     * @throws IllegalArgumentException if the order is not a stop order
     */
    void addStopOrder(Order order);

    /**
     * Removes a dormant stop order (e.g. a cancellation before it ever triggers).
     *
     * @return {@code true} if the stop order was present and removed
     */
    boolean removeStopOrder(Order order);

    /**
     * Removes and returns every stop order whose trigger is fired by the market having traded at
     * the given price, in activation-priority order. The returned orders are no longer held by the
     * book; the caller is responsible for activating them.
     *
     * @param lastTradePrice the price the market just traded at
     * @return the fired stops, or an empty list if none triggered
     */
    List<Order> pollTriggeredStops(BigDecimal lastTradePrice);

    /**
     * @return a read-only view of the dormant buy stop orders, closest trigger first, keyed by
     *         {@link Order#id()}
     */
    NavigableMap<UUID, Order> buyStopOrders();

    /**
     * @return a read-only view of the dormant sell stop orders, closest trigger first, keyed by
     *         {@link Order#id()}
     */
    NavigableMap<UUID, Order> sellStopOrders();

    /**
     * Calculates the bid-ask spread: the best sell price minus the best buy price. Empty unless
     * both sides have a resting order, or when the best order on either side is a priceless
     * market order.
     *
     * @return the spread, or empty if it cannot be determined
     */
    default Optional<BigDecimal> spread() {
        return bestBuy().filter(buy -> !buy.isMarket()).flatMap(buy ->
                bestSell().filter(sell -> !sell.isMarket()).map(sell -> sell.price().subtract(buy.price())));
    }

    /**
     * @return the total resting quantity on the given side of the book, across every price level
     *         (market orders included)
     */
    default BigDecimal availableLiquidity(Side side) {
        Objects.requireNonNull(side, "side must not be null");
        return sideView(side).values().stream()
                .map(Order::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Computes the resting quantity on the given side that an aggressor with the given limit price
     * could execute against: every resting market order (they trade at any price), plus every
     * resting limit order priced at the aggressor's limit or better. Because each side is ordered
     * best-first, the scan stops at the first limit order the aggressor's price cannot reach.
     *
     * @param side       the resting side to measure ({@code SELL} for an incoming buy,
     *                   {@code BUY} for an incoming sell)
     * @param limitPrice the aggressor's limit price
     * @return the total quantity executable at {@code limitPrice} or better
     */
    default BigDecimal availableLiquidity(Side side, BigDecimal limitPrice) {
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(limitPrice, "limitPrice must not be null");

        BigDecimal total = BigDecimal.ZERO;
        for (Order resting : sideView(side).values()) {
            if (!resting.isMarket() && !executableAt(resting, limitPrice)) {
                break; // sorted best-first: every later order is priced worse
            }
            total = total.add(resting.quantity());
        }
        return total;
    }

    /**
     * Builds the standard market-depth (level-2) view of this book: the top-N bid and ask price
     * levels, each aggregated by total resting size and order count. Resting market orders carry
     * no price and are excluded.
     *
     * @param maxLevels the N in top-N: at most this many levels per side
     * @return the aggregated depth view, best level first on both sides
     */
    default MarketDepth depth(int maxLevels) {
        return MarketDepth.of(buyOrders().values(), sellOrders().values(), maxLevels);
    }

    private Map<UUID, Order> sideView(Side side) {
        return side == Side.BUY ? buyOrders() : sellOrders();
    }

    /**
     * Whether a resting limit order is executable against an aggressor limited to
     * {@code limitPrice}: a resting sell must ask no more than it, a resting buy must bid no less.
     */
    private static boolean executableAt(Order resting, BigDecimal limitPrice) {
        return resting.side() == Side.SELL
                ? resting.price().compareTo(limitPrice) <= 0
                : resting.price().compareTo(limitPrice) >= 0;
    }
}
