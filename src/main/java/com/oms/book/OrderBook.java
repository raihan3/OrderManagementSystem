package com.oms.book;

import com.oms.model.Order;
import com.oms.model.Side;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 */
public class OrderBook {

    private final Map<UUID, Order> ordersById = new HashMap<>();

    private final NavigableMap<UUID, Order> buyOrders = new TreeMap<>(orderIdComparator(true));
    private final NavigableMap<UUID, Order> sellOrders = new TreeMap<>(orderIdComparator(false));

    /**
     * Adds an order to the buy or sell side of the book, based on its {@link Order#side()}.
     * A resting {@link com.oms.model.OrderType#MARKET} order ranks ahead of all limit orders on
     * its side, ordered among other market orders by arrival time.
     */
    public void addOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        ordersById.put(order.id(), order);
        mapFor(order.side()).put(order.id(), order);
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

    private NavigableMap<UUID, Order> mapFor(Side side) {
        return switch (side) {
            case BUY -> buyOrders;
            case SELL -> sellOrders;
        };
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
