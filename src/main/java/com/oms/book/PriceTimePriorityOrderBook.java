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
 * The standard {@link OrderBook} implementation: resting orders in market-then-price-time
 * priority, backed by {@link TreeMap}s.
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
 * <p>Alongside the resting book, dormant stop orders live in two separate stop lists (one per
 * side), ordered by trigger proximity: buy stops ascending by stop price (the lowest trigger
 * fires first as the market rises), sell stops descending (the highest trigger fires first as the
 * market falls), with time then id breaking ties. That ordering makes the stops triggered by a
 * traded price a strict prefix of each list, so {@link #pollTriggeredStops(BigDecimal)} drains
 * exactly the fired stops without scanning the rest.
 */
public class PriceTimePriorityOrderBook implements OrderBook {

    private final Map<UUID, Order> ordersById = new HashMap<>();

    private final NavigableMap<UUID, Order> buyOrders = new TreeMap<>(orderIdComparator(true));
    private final NavigableMap<UUID, Order> sellOrders = new TreeMap<>(orderIdComparator(false));

    private final Map<UUID, Order> stopOrdersById = new HashMap<>();

    private final NavigableMap<UUID, Order> buyStopOrders = new TreeMap<>(stopOrderIdComparator(true));
    private final NavigableMap<UUID, Order> sellStopOrders = new TreeMap<>(stopOrderIdComparator(false));

    @Override
    public void addOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        if (order.isStop()) {
            throw new IllegalArgumentException(
                    "a " + order.type() + " order must be parked via addStopOrder until triggered: " + order.id());
        }
        ordersById.put(order.id(), order);
        mapFor(order.side()).put(order.id(), order);
    }

    @Override
    public void addStopOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        if (!order.isStop()) {
            throw new IllegalArgumentException(
                    "a " + order.type() + " order has no trigger and cannot be parked as a stop: " + order.id());
        }
        stopOrdersById.put(order.id(), order);
        stopMapFor(order.side()).put(order.id(), order);
    }

    @Override
    public boolean removeStopOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        boolean removed = stopMapFor(order.side()).remove(order.id()) != null;
        if (removed) {
            stopOrdersById.remove(order.id());
        }
        return removed;
    }

    @Override
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
     * {@inheritDoc}
     *
     * <p>The amendment is expressed against a builder seeded from the existing order (see
     * {@link Order#toBuilder()}), so callers change only the fields they care about, e.g.
     * <pre>{@code book.updateOrder(id, b -> b.price(newPrice).quantity(newQty));}</pre>
     */
    @Override
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

    @Override
    public boolean removeOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        boolean removed = mapFor(order.side()).remove(order.id()) != null;
        if (removed) {
            ordersById.remove(order.id());
        }
        return removed;
    }

    @Override
    public Optional<Order> bestBuy() {
        var entry = buyOrders.firstEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    @Override
    public Optional<Order> bestSell() {
        var entry = sellOrders.firstEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    @Override
    public NavigableMap<UUID, Order> buyOrders() {
        return Collections.unmodifiableNavigableMap(buyOrders);
    }

    @Override
    public NavigableMap<UUID, Order> sellOrders() {
        return Collections.unmodifiableNavigableMap(sellOrders);
    }

    @Override
    public NavigableMap<UUID, Order> buyStopOrders() {
        return Collections.unmodifiableNavigableMap(buyStopOrders);
    }

    @Override
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
     * The {@link TreeMap} keys are ids, so the shared {@link BookOrdering} comparators are lifted
     * to id comparators by resolving each id back to its order via {@link #stopOrdersById}.
     */
    private Comparator<UUID> stopOrderIdComparator(boolean buySide) {
        return Comparator.comparing(stopOrdersById::get, BookOrdering.stopOrder(buySide));
    }

    private Comparator<UUID> orderIdComparator(boolean buySide) {
        return Comparator.comparing(ordersById::get, BookOrdering.restingOrder(buySide));
    }
}
