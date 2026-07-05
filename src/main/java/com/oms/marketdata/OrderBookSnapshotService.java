package com.oms.marketdata;

import com.oms.book.OrderBookRegistry;
import com.oms.book.OrderBookSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Periodically captures immutable {@link OrderBookSnapshot}s of every book in the
 * {@link OrderBookRegistry}, at an interval fixed when the application is wired together.
 *
 * <p>Snapshotting is <b>in-band</b>: the engine calls {@link #tick()} on its own matching thread
 * after processing each order, and a snapshot cycle runs only when the configured interval has
 * elapsed since the last one. This is how single-threaded matching engines take consistent
 * snapshots without locks — a background timer thread reading the books concurrently would race
 * with matching. The price of the in-band model is that a completely idle engine takes no
 * snapshots, which is harmless: an unchanged book needs no new snapshot.
 *
 * <p>Each cycle stamps snapshots with a venue-wide monotonic sequence and one shared timestamp,
 * visits symbols in sorted order for determinism, keeps the latest snapshot per symbol for
 * {@link #latestSnapshot(String)} queries, and hands every snapshot to the publisher (e.g. a
 * market-data feed or persistent store).
 *
 * <p>Not thread-safe: like the books themselves, this service must only be used from the matching
 * thread that owns them.
 */
public class OrderBookSnapshotService {

    private final OrderBookRegistry orderBooks;
    private final Duration interval;
    private final Clock clock;
    private final SnapshotPublisher publisher;
    private final Map<String, OrderBookSnapshot> latestBySymbol = new HashMap<>();

    private Instant lastSnapshotAt;
    private long sequence;

    /**
     * Creates a service that only records the latest snapshot per symbol, without publishing.
     */
    public OrderBookSnapshotService(OrderBookRegistry orderBooks, Duration interval, Clock clock) {
        this(orderBooks, interval, clock, snapshot -> { });
    }

    /**
     * @param orderBooks the books to snapshot
     * @param interval   how often a snapshot cycle runs; fixed for the life of the service
     *                   ({@link Duration#ZERO} snapshots on every tick). Chosen at application
     *                   start.
     * @param clock      the time source for interval measurement and snapshot timestamps
     * @param publisher  receives every captured snapshot, e.g. a feed or store
     */
    public OrderBookSnapshotService(OrderBookRegistry orderBooks, Duration interval, Clock clock,
                                    SnapshotPublisher publisher) {
        this.orderBooks = Objects.requireNonNull(orderBooks, "orderBooks must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        if (interval.isNegative()) {
            throw new IllegalArgumentException("interval must not be negative, but was " + interval);
        }
        this.lastSnapshotAt = clock.instant();
    }

    /**
     * Called by the engine after each processed order. Runs a snapshot cycle if the configured
     * interval has elapsed since the last one; otherwise does nothing.
     */
    public void tick() {
        Instant now = clock.instant();
        if (Duration.between(lastSnapshotAt, now).compareTo(interval) < 0) {
            return;
        }
        snapshotNow();
    }

    /**
     * Runs a snapshot cycle immediately, regardless of the interval (e.g. end-of-day capture or a
     * late joiner requesting a full refresh), and resets the interval timer.
     *
     * @return the snapshots captured this cycle, one per registered symbol, in symbol order
     */
    public List<OrderBookSnapshot> snapshotNow() {
        Instant now = clock.instant();
        List<OrderBookSnapshot> cycle = new ArrayList<>();
        for (String symbol : orderBooks.symbols().stream().sorted().toList()) {
            OrderBookSnapshot snapshot =
                    OrderBookSnapshot.capture(symbol, orderBooks.bookFor(symbol), ++sequence, now);
            latestBySymbol.put(symbol, snapshot);
            publisher.publish(snapshot);
            cycle.add(snapshot);
        }
        lastSnapshotAt = now;
        return List.copyOf(cycle);
    }

    /**
     * @return the most recent snapshot captured for the symbol, if any cycle has covered it yet
     */
    public Optional<OrderBookSnapshot> latestSnapshot(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return Optional.ofNullable(latestBySymbol.get(symbol));
    }

    /**
     * @return the snapshot interval this service was configured with at application start
     */
    public Duration interval() {
        return interval;
    }
}
