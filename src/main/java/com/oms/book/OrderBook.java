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
 * Maintains the resting buy and sell orders for a single instrument in price-time priority.
 *
 * <p>Buy orders are kept in descending price order, with the earliest timestamp winning ties
 * (highest bid first). Sell orders are kept in ascending price order, with the earliest
 * timestamp winning ties (lowest ask first). Each comparator falls back to comparing
 * {@link Order#id()} so that two distinct orders sharing the same price and timestamp do not
 * collide as equal keys within the underlying {@link TreeMap}.
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
     * @return the highest-priced (best) resting buy order, if any
     */
    public Optional<Order> bestBuy() {
        var entry = buyOrders.firstEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    /**
     * @return the lowest-priced (best) resting sell order, if any
     */
    public Optional<Order> bestSell() {
        var entry = sellOrders.firstEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    /**
     * Calculates the bid-ask spread: the best (lowest) sell price minus the best (highest) buy
     * price. The result is empty unless both sides have at least one resting order, since the
     * spread is undefined otherwise.
     *
     * @return the spread, or empty if either side of the book is empty
     */
    public Optional<BigDecimal> spread() {
        return bestBuy().flatMap(buy ->
                bestSell().map(sell -> sell.price().subtract(buy.price())));
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
        Comparator<Order> byPrice = buySide
                ? Comparator.comparing(Order::price).reversed()
                : Comparator.comparing(Order::price);
        Comparator<Order> byPriceThenTime = byPrice.thenComparing(Order::timestamp).thenComparing(Order::id);
        return Comparator.comparing(ordersById::get, byPriceThenTime);
    }
}
