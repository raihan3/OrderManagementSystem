package com.oms.book;

import com.oms.model.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, point-in-time copy of one symbol's {@link OrderBook}: the resting orders on both
 * sides in priority order (best first), plus the dormant stop orders in trigger-proximity order.
 *
 * <p>Snapshots are the unit of periodic market-data publication and recovery: a consumer that
 * receives a snapshot holds a complete, self-consistent view of the book as of
 * {@link #timestamp()}, and the venue-wide monotonic {@link #sequence()} lets consumers order
 * snapshots and detect gaps. Because {@link Order} is immutable and the lists are defensively
 * copied, a snapshot is unaffected by anything the live book does afterwards.
 *
 * @param symbol         the instrument this snapshot describes
 * @param sequence       venue-wide monotonically increasing snapshot sequence number
 * @param timestamp      the instant the snapshot was taken
 * @param buyOrders      resting buy orders, best (highest priority) first
 * @param sellOrders     resting sell orders, best first
 * @param buyStopOrders  dormant buy stops, closest trigger first
 * @param sellStopOrders dormant sell stops, closest trigger first
 */
public record OrderBookSnapshot(
        String symbol,
        long sequence,
        Instant timestamp,
        List<Order> buyOrders,
        List<Order> sellOrders,
        List<Order> buyStopOrders,
        List<Order> sellStopOrders
) {

    public OrderBookSnapshot {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        buyOrders = List.copyOf(buyOrders);
        sellOrders = List.copyOf(sellOrders);
        buyStopOrders = List.copyOf(buyStopOrders);
        sellStopOrders = List.copyOf(sellStopOrders);
    }

    /**
     * Captures the current state of a book. Must be invoked from the thread that owns the book
     * (the matching thread), which makes the copy self-consistent without locking.
     */
    public static OrderBookSnapshot capture(String symbol, OrderBook book, long sequence, Instant asOf) {
        Objects.requireNonNull(book, "book must not be null");
        return new OrderBookSnapshot(
                symbol,
                sequence,
                asOf,
                List.copyOf(book.buyOrders().values()),
                List.copyOf(book.sellOrders().values()),
                List.copyOf(book.buyStopOrders().values()),
                List.copyOf(book.sellStopOrders().values()));
    }

    /**
     * @return the best resting buy order at snapshot time, if any
     */
    public Optional<Order> bestBuy() {
        return buyOrders.isEmpty() ? Optional.empty() : Optional.of(buyOrders.getFirst());
    }

    /**
     * @return the best resting sell order at snapshot time, if any
     */
    public Optional<Order> bestSell() {
        return sellOrders.isEmpty() ? Optional.empty() : Optional.of(sellOrders.getFirst());
    }

    /**
     * @return the bid-ask spread at snapshot time, mirroring {@link OrderBook#spread()}: empty if
     *         either side is empty or its best order is a priceless market order
     */
    public Optional<BigDecimal> spread() {
        return bestBuy().filter(buy -> !buy.isMarket()).flatMap(buy ->
                bestSell().filter(sell -> !sell.isMarket()).map(sell -> sell.price().subtract(buy.price())));
    }

    /**
     * Builds the market-depth (level-2) view of the book as of this snapshot, mirroring
     * {@link OrderBook#depth(int)}: the top-N bid and ask price levels aggregated by total resting
     * size, excluding priceless market orders.
     *
     * @param maxLevels the N in top-N: at most this many levels per side
     * @return the aggregated depth view, best level first on both sides
     */
    public MarketDepth depth(int maxLevels) {
        return MarketDepth.of(buyOrders, sellOrders, maxLevels);
    }
}
