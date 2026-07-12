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
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * An {@link OrderBook} whose sides are binary heaps ({@link PriorityQueue}) instead of
 * {@link TreeMap}s, using the same {@link BookOrdering} priority semantics.
 *
 * <p>Heaps trade differently from balanced trees:
 * <ul>
 *   <li><b>Top-of-book is what heaps are for</b> — {@code peek} is O(1) and insert is O(log n)
 *       with a flat array layout (better constants and cache behaviour than tree nodes).</li>
 *   <li><b>Arbitrary removal is not supported cheaply</b>, so cancels and amendments use
 *       <i>lazy deletion</i>: the order is dropped from the live index ({@link #ordersById}) and
 *       its heap entry becomes a tombstone, discarded when it surfaces at the head. When
 *       tombstones outnumber live entries the heap is compacted in one pass, keeping the cost
 *       amortised.</li>
 *   <li><b>Heaps have no sorted iteration</b>, so the {@link #buyOrders()}/{@link #sellOrders()}
 *       views (and the stop views) are point-in-time <b>copies built on demand</b> in
 *       O(n&nbsp;log&nbsp;n) — unlike the TreeMap book's O(1) live views. Everything derived from
 *       the views (depth, liquidity scans, snapshots) pays that price per call.</li>
 * </ul>
 *
 * <p>An entry is live iff the live index still maps its id to <b>that exact instance</b>;
 * amendments replace the mapping with the rebuilt order, instantly tombstoning the old heap entry
 * wherever it sits. Not thread-safe, like every book: one matching thread owns it.
 */
public class PriorityQueueOrderBook implements OrderBook {

    private final Map<UUID, Order> ordersById = new HashMap<>();
    private final LazyDeleteHeap buyOrders = new LazyDeleteHeap(BookOrdering.restingOrder(true), ordersById);
    private final LazyDeleteHeap sellOrders = new LazyDeleteHeap(BookOrdering.restingOrder(false), ordersById);

    private final Map<UUID, Order> stopOrdersById = new HashMap<>();
    private final LazyDeleteHeap buyStopOrders = new LazyDeleteHeap(BookOrdering.stopOrder(true), stopOrdersById);
    private final LazyDeleteHeap sellStopOrders = new LazyDeleteHeap(BookOrdering.stopOrder(false), stopOrdersById);

    @Override
    public void addOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        if (order.isStop()) {
            throw new IllegalArgumentException(
                    "a " + order.type() + " order must be parked via addStopOrder until triggered: " + order.id());
        }
        ordersById.put(order.id(), order);
        heapFor(order.side()).offer(order);
    }

    @Override
    public boolean removeOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        Order live = ordersById.remove(order.id());
        if (live == null) {
            return false;
        }
        heapFor(live.side()).markStale();
        return true;
    }

    @Override
    public Order updateOrder(UUID id, UnaryOperator<Order.Builder> amendment) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(amendment, "amendment must not be null");

        Order existing = ordersById.get(id);
        if (existing == null) {
            throw new NoSuchElementException("No order in the book with id " + id);
        }

        Order updated = amendment.apply(existing.toBuilder()).id(id).build();

        // Re-mapping the id tombstones the old heap entry (it no longer matches the live index);
        // the amended order is offered to whichever side it now belongs to.
        ordersById.put(id, updated);
        heapFor(existing.side()).markStale();
        heapFor(updated.side()).offer(updated);
        return updated;
    }

    @Override
    public Optional<Order> bestBuy() {
        return Optional.ofNullable(buyOrders.peekLive());
    }

    @Override
    public Optional<Order> bestSell() {
        return Optional.ofNullable(sellOrders.peekLive());
    }

    @Override
    public NavigableMap<UUID, Order> buyOrders() {
        return sortedCopy(ordersById, Side.BUY, BookOrdering.restingOrder(true));
    }

    @Override
    public NavigableMap<UUID, Order> sellOrders() {
        return sortedCopy(ordersById, Side.SELL, BookOrdering.restingOrder(false));
    }

    @Override
    public void addStopOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        if (!order.isStop()) {
            throw new IllegalArgumentException(
                    "a " + order.type() + " order has no trigger and cannot be parked as a stop: " + order.id());
        }
        stopOrdersById.put(order.id(), order);
        stopHeapFor(order.side()).offer(order);
    }

    @Override
    public boolean removeStopOrder(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        Order live = stopOrdersById.remove(order.id());
        if (live == null) {
            return false;
        }
        stopHeapFor(live.side()).markStale();
        return true;
    }

    @Override
    public List<Order> pollTriggeredStops(BigDecimal lastTradePrice) {
        Objects.requireNonNull(lastTradePrice, "lastTradePrice must not be null");
        List<Order> triggered = new ArrayList<>();
        drainTriggered(buyStopOrders, lastTradePrice, triggered);
        drainTriggered(sellStopOrders, lastTradePrice, triggered);
        return triggered;
    }

    private void drainTriggered(LazyDeleteHeap stops, BigDecimal lastTradePrice, List<Order> out) {
        Order head;
        while ((head = stops.peekLive()) != null && head.isStopTriggeredAt(lastTradePrice)) {
            stops.pollLiveHead();
            stopOrdersById.remove(head.id());
            out.add(head);
        }
    }

    @Override
    public NavigableMap<UUID, Order> buyStopOrders() {
        return sortedCopy(stopOrdersById, Side.BUY, BookOrdering.stopOrder(true));
    }

    @Override
    public NavigableMap<UUID, Order> sellStopOrders() {
        return sortedCopy(stopOrdersById, Side.SELL, BookOrdering.stopOrder(false));
    }

    private LazyDeleteHeap heapFor(Side side) {
        return switch (side) {
            case BUY -> buyOrders;
            case SELL -> sellOrders;
        };
    }

    private LazyDeleteHeap stopHeapFor(Side side) {
        return switch (side) {
            case BUY -> buyStopOrders;
            case SELL -> sellStopOrders;
        };
    }

    /**
     * Builds a point-in-time, priority-ordered copy of one side from the live index. O(n log n)
     * per call — the cost of asking a heap for sorted iteration.
     */
    private static NavigableMap<UUID, Order> sortedCopy(Map<UUID, Order> liveIndex, Side side,
                                                        Comparator<Order> priority) {
        Map<UUID, Order> ofSide = new HashMap<>();
        for (Order order : liveIndex.values()) {
            if (order.side() == side) {
                ofSide.put(order.id(), order);
            }
        }
        TreeMap<UUID, Order> view = new TreeMap<>(Comparator.comparing(ofSide::get, priority));
        view.putAll(ofSide);
        return Collections.unmodifiableNavigableMap(view);
    }

    /**
     * A binary heap with lazy deletion. An entry is live iff the shared live index still maps its
     * id to that exact instance; anything else is a tombstone. Tombstones are discarded when they
     * surface at the head, and the heap is compacted in one pass when they outnumber live entries,
     * keeping cancel/amend amortised O(log n).
     */
    private static final class LazyDeleteHeap {

        private static final int COMPACTION_THRESHOLD = 64;

        private final PriorityQueue<Order> queue;
        private final Map<UUID, Order> liveIndex;
        private int staleCount;

        LazyDeleteHeap(Comparator<Order> priority, Map<UUID, Order> liveIndex) {
            this.queue = new PriorityQueue<>(priority);
            this.liveIndex = liveIndex;
        }

        void offer(Order order) {
            queue.offer(order);
        }

        /** Notes that one of this heap's entries just became a tombstone; compacts when due. */
        void markStale() {
            staleCount++;
            if (staleCount > COMPACTION_THRESHOLD && staleCount > queue.size() / 2) {
                compact();
            }
        }

        /** The live head of the heap, discarding any tombstones that have surfaced. */
        Order peekLive() {
            while (true) {
                Order head = queue.peek();
                if (head == null) {
                    return null;
                }
                if (liveIndex.get(head.id()) == head) {
                    return head;
                }
                queue.poll(); // surfaced tombstone
                staleCount--;
            }
        }

        /** Removes the current live head (callers manage the live index themselves). */
        void pollLiveHead() {
            if (peekLive() != null) {
                queue.poll();
            }
        }

        private void compact() {
            List<Order> live = new ArrayList<>(Math.max(queue.size() - staleCount, 0));
            for (Order order : queue) {
                if (liveIndex.get(order.id()) == order) {
                    live.add(order);
                }
            }
            queue.clear();
            queue.addAll(live);
            staleCount = 0;
        }
    }
}
