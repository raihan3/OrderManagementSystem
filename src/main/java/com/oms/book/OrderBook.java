package com.oms.book;

import com.oms.model.Order;
import com.oms.model.Side;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Maintains the resting buy and sell orders for a single instrument in market-then-price-time
 * priority.
 *
 * <p>Resting {@link com.oms.model.OrderType#MARKET} orders rank ahead of all limit orders on their
 * side and are ordered among themselves by earliest arrival time. Limit orders follow, in
 * descending price for buys (highest bid first) and ascending price for sells (lowest ask first),
 * with the earliest timestamp winning ties. Each comparator falls back to comparing
 * {@link Order#id()} so that two distinct orders sharing the same rank do not collide as equal
 * keys within the underlying {@link TreeMap}.
 *
 * <p>Each map is keyed by {@link Order#id()} rather than the order itself; since a
 * {@code TreeMap}'s ordering comparator only ever sees the keys, the comparators resolve each
 * id back to its {@link Order} via {@link #ordersById} to compare price and timestamp.
 *
 * <p>Alongside the resting book, the book also holds dormant stop orders in two separate stop
 * lists (one per side), ordered by trigger proximity: buy stops ascending by stop price (the
 * lowest trigger fires first as the market rises), sell stops descending (the highest trigger
 * fires first as the market falls), with time then id breaking ties. That ordering makes the
 * stops triggered by a traded price a strict prefix of each list, so
 * {@link #pollTriggeredStops(BigDecimal)} drains exactly the fired stops without scanning the
 * rest.
 */
public class OrderBook {

    private final Map<UUID, Order> ordersById = new HashMap<>();

    private final NavigableMap<UUID, Order> buyOrders = new TreeMap<>(orderIdComparator(true));
    private final NavigableMap<UUID, Order> sellOrders = new TreeMap<>(orderIdComparator(false));

    private final Map<UUID, Order> stopOrdersById = new HashMap<>();

    private final NavigableMap<UUID, Order> buyStopOrders = new TreeMap<>(stopOrderIdComparator(true));
    private final NavigableMap<UUID, Order> sellStopOrders = new TreeMap<>(stopOrderIdComparator(false));

    /**
     * Adds an order to the buy or sell side of the book, based on its {@link Order#side()}.
     * A resting {@link com.oms.model.OrderType#MARKET} order ranks ahead of all limit orders on
     * its side, ordered among other market orders by arrival time.
     *
     * @throws IllegalArgumentException if the order is a stop order — dormant stops belong in the
     *                                  stop lists via {@link #addStopOrder(Order)}, not on the book
     */
    public void addOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        if (order.isStop()) {
            throw new IllegalArgumentException(
                    "a " + order.type() + " order must be parked via addStopOrder until triggered: " + order.id());
        }
        ordersById.put(order.id(), order);
        mapFor(order.side()).put(order.id(), order);
    }

    /**
     * Parks a dormant stop order in the stop list for its side, ordered by trigger proximity and
     * then arrival time. It stays there — invisible to matching — until
     * {@link #pollTriggeredStops(BigDecimal)} releases it.
     *
     * @throws IllegalArgumentException if the order is not a stop order
     */
    public void addStopOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        if (!order.isStop()) {
            throw new IllegalArgumentException(
                    "a " + order.type() + " order has no trigger and cannot be parked as a stop: " + order.id());
        }
        stopOrdersById.put(order.id(), order);
        stopMapFor(order.side()).put(order.id(), order);
    }

    /**
     * Removes a dormant stop order (e.g. a cancellation before it ever triggers).
     *
     * @return {@code true} if the stop order was present and removed
     */
    public boolean removeStopOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        boolean removed = stopMapFor(order.side()).remove(order.id()) != null;
        if (removed) {
            stopOrdersById.remove(order.id());
        }
        return removed;
    }

    /**
     * Removes and returns every stop order whose trigger is fired by the market having traded at
     * the given price: buy stops with a stop price at or below it, sell stops with a stop price at
     * or above it.
     *
     * <p>Each stop list is ordered by trigger proximity, so the fired stops are a prefix of each
     * list and the scan stops at the first stop that does not trigger. Returned orders are in
     * activation-priority order (buy stops first, then sell stops, each closest-trigger-first and
     * time-priority within a trigger price); they are no longer held by the book, so the caller is
     * responsible for activating them.
     *
     * @param lastTradePrice the price the market just traded at
     * @return the fired stops, or an empty list if none triggered
     */
    public List<Order> pollTriggeredStops(BigDecimal lastTradePrice) {
        Objects.requireNonNull(lastTradePrice, "lastTradePrice must not be null");
        List<Order> triggered = new ArrayList<>();
        drainTriggered(buyStopOrders, lastTradePrice, triggered);
        drainTriggered(sellStopOrders, lastTradePrice, triggered);
        return triggered;
    }

    private void drainTriggered(NavigableMap<UUID, Order> stops, BigDecimal lastTradePrice, List<Order> out) {
        while (!stops.isEmpty() && stops.firstEntry().getValue().isStopTriggeredAt(lastTradePrice)) {
            Order stop = stops.pollFirstEntry().getValue();
            stopOrdersById.remove(stop.id());
            out.add(stop);
        }
    }

    /**
     * Amends the details of a resting order, keyed by its {@link Order#id()}.
     *
     * <p>The amendment is expressed against a builder seeded from the existing order (see
     * {@link Order#toBuilder()}), so callers change only the fields they care about, e.g.
     * <pre>{@code book.updateOrder(id, b -> b.price(newPrice).quantity(newQty));}</pre>
     *
     * <p>The order's id is preserved regardless of what the amendment sets. Because the amended
     * order may alter its price, timestamp, or even side, it is fully re-placed in the book: the
     * old entry is removed first, then the new one inserted into whichever side it now belongs to.
     *
     * @param id        id of the order to amend
     * @param amendment mutation applied to a builder pre-populated with the existing order
     * @return the amended order
     * @throws NoSuchElementException if no order with the given id is present
     */
    public Order updateOrder(UUID id, UnaryOperator<Order.Builder> amendment) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(amendment, "amendment must not be null");

        Order existing = ordersById.get(id);
        if (existing == null) {
            throw new NoSuchElementException("No order in the book with id " + id);
        }

        Order updated = amendment.apply(existing.toBuilder()).id(id).build();

        // Remove the stale entry before swapping ordersById, so the TreeMap comparator can still
        // resolve the old key; then insert the amended order (its side may have changed).
        removeOrder(existing);
        addOrder(updated);
        return updated;
    }

    /**
     * Removes an order from the book.
     *
     * @return {@code true} if the order was present and removed
     */
    public boolean removeOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        boolean removed = mapFor(order.side()).remove(order.id()) != null;
        if (removed) {
            ordersById.remove(order.id());
        }
        return removed;
    }

    /**
     * @return the best resting buy order (a market order if one rests, otherwise the highest-priced
     *         limit), if any
     */
    public Optional<Order> bestBuy() {
        var entry = buyOrders.firstEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    /**
     * @return the best resting sell order (a market order if one rests, otherwise the lowest-priced
     *         limit), if any
     */
    public Optional<Order> bestSell() {
        var entry = sellOrders.firstEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    /**
     * Calculates the bid-ask spread: the best (lowest) sell price minus the best (highest) buy
     * price. The result is empty unless both sides have at least one resting order; it is also
     * empty when the best order on either side is a market order, since a market order carries no
     * price and the spread is therefore undefined.
     *
     * @return the spread, or empty if it cannot be determined
     */
    public Optional<BigDecimal> spread() {
        return bestBuy().filter(buy -> !buy.isMarket()).flatMap(buy ->
                bestSell().filter(sell -> !sell.isMarket()).map(sell -> sell.price().subtract(buy.price())));
    }

    /**
     * @return the total resting quantity on the given side of the book, across every price level
     *         (market orders included)
     */
    public BigDecimal availableLiquidity(Side side) {
        Objects.requireNonNull(side, "side must not be null");
        return mapFor(side).values().stream()
                .map(Order::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Computes the resting quantity on the given side that an aggressor with the given limit price
     * could execute against: every resting market order (they trade at any price), plus every
     * resting limit order priced at the aggressor's limit or better.
     *
     * <p>Because each side is sorted best-first (market orders, then best-priced limits), the scan
     * stops at the first limit order the aggressor's price cannot reach — no later order can be
     * executable either — so only the executable prefix of the book is visited.
     *
     * @param side       the resting side to measure ({@code SELL} for an incoming buy,
     *                   {@code BUY} for an incoming sell)
     * @param limitPrice the aggressor's limit price
     * @return the total quantity executable at {@code limitPrice} or better
     */
    public BigDecimal availableLiquidity(Side side, BigDecimal limitPrice) {
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(limitPrice, "limitPrice must not be null");

        BigDecimal total = BigDecimal.ZERO;
        for (Order resting : mapFor(side).values()) {
            if (!resting.isMarket() && !executableAt(resting, limitPrice)) {
                break; // sorted best-first: every later order is priced worse
            }
            total = total.add(resting.quantity());
        }
        return total;
    }

    /**
     * @return whether a resting limit order is executable against an aggressor limited to
     *         {@code limitPrice}: a resting sell must ask no more than it, a resting buy must bid
     *         no less than it
     */
    private static boolean executableAt(Order resting, BigDecimal limitPrice) {
        return resting.side() == Side.SELL
                ? resting.price().compareTo(limitPrice) <= 0
                : resting.price().compareTo(limitPrice) >= 0;
    }

    /**
     * @return a read-only, price-time-ordered view of the resting buy orders, keyed by
     *         {@link Order#id()}
     */
    public NavigableMap<UUID, Order> buyOrders() {
        return Collections.unmodifiableNavigableMap(buyOrders);
    }

    /**
     * @return a read-only, price-time-ordered view of the resting sell orders, keyed by
     *         {@link Order#id()}
     */
    public NavigableMap<UUID, Order> sellOrders() {
        return Collections.unmodifiableNavigableMap(sellOrders);
    }

    /**
     * @return a read-only view of the dormant buy stop orders, ordered lowest trigger first,
     *         keyed by {@link Order#id()}
     */
    public NavigableMap<UUID, Order> buyStopOrders() {
        return Collections.unmodifiableNavigableMap(buyStopOrders);
    }

    /**
     * @return a read-only view of the dormant sell stop orders, ordered highest trigger first,
     *         keyed by {@link Order#id()}
     */
    public NavigableMap<UUID, Order> sellStopOrders() {
        return Collections.unmodifiableNavigableMap(sellStopOrders);
    }

    private NavigableMap<UUID, Order> mapFor(Side side) {
        return switch (side) {
            case BUY -> buyOrders;
            case SELL -> sellOrders;
        };
    }

    private NavigableMap<UUID, Order> stopMapFor(Side side) {
        return switch (side) {
            case BUY -> buyStopOrders;
            case SELL -> sellStopOrders;
        };
    }

    /**
     * Orders each stop list by trigger proximity: buy stops fire as the market rises, so the
     * lowest stop price triggers first; sell stops fire as the market falls, so the highest stop
     * price triggers first. Ties break on time then id, mirroring the resting book.
     */
    private Comparator<UUID> stopOrderIdComparator(boolean buySide) {
        Comparator<Order> byTriggerProximity = buySide
                ? Comparator.comparing(Order::stopPrice)
                : Comparator.comparing(Order::stopPrice).reversed();
        Comparator<Order> stopComparator =
                byTriggerProximity.thenComparing(Order::timestamp).thenComparing(Order::id);
        return Comparator.comparing(stopOrdersById::get, stopComparator);
    }

    private Comparator<UUID> orderIdComparator(boolean buySide) {
        // Market orders rank ahead of all limit orders; the id tie-break keeps distinct orders
        // that share a rank from colliding as equal keys in the TreeMap.
        Comparator<Order> marketBeforeLimit = Comparator.comparing((Order o) -> o.isMarket() ? 0 : 1);
        Comparator<Order> byTime = Comparator.comparing(Order::timestamp).thenComparing(Order::id);
        Comparator<Order> byPrice = buySide
                ? Comparator.comparing(Order::price).reversed()  // highest bid first
                : Comparator.comparing(Order::price);            // lowest ask first
        Comparator<Order> byPriceThenTime = byPrice.thenComparing(byTime);

        // Once market-vs-limit ties, both orders are the same kind: market orders compare on time
        // only (they have no price), limit orders on price then time.
        Comparator<Order> withinGroup = (a, b) ->
                a.isMarket() ? byTime.compare(a, b) : byPriceThenTime.compare(a, b);

        Comparator<Order> orderComparator = marketBeforeLimit.thenComparing(withinGroup);
        return Comparator.comparing(ordersById::get, orderComparator);
    }
}
