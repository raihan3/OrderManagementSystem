package com.oms.marketdata;

import com.oms.book.InMemoryOrderBookRegistry;
import com.oms.book.OrderBookRegistry;
import com.oms.book.OrderBookSnapshot;
import com.oms.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.oms.support.TestOrders.at;
import static com.oms.support.TestOrders.buy;
import static com.oms.support.TestOrders.sell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookSnapshotServiceTest {

    private static final Duration INTERVAL = Duration.ofSeconds(10);

    private OrderBookRegistry registry;
    private MutableClock clock;
    private List<OrderBookSnapshot> published;
    private OrderBookSnapshotService service;

    @BeforeEach
    void setUp() {
        registry = new InMemoryOrderBookRegistry();
        clock = new MutableClock(Instant.parse("2026-07-05T09:00:00Z"));
        published = new ArrayList<>();
        service = new OrderBookSnapshotService(registry, INTERVAL, clock, published::add);
    }

    @Test
    void doesNotSnapshotBeforeTheIntervalElapses() {
        registry.bookFor("AAPL").addOrder(buy("1", "100", at(0)));

        clock.advance(Duration.ofSeconds(9));
        service.tick();

        assertTrue(published.isEmpty());
        assertEquals(Optional.empty(), service.latestSnapshot("AAPL"));
    }

    @Test
    void snapshotsEveryRegisteredSymbolOnceTheIntervalElapses() {
        registry.bookFor("MSFT").addOrder(sell("MSFT", "2", "250", at(0)));
        registry.bookFor("AAPL").addOrder(buy("1", "100", at(1)));

        clock.advance(INTERVAL);
        service.tick();

        assertEquals(2, published.size());
        // Symbols are visited in sorted order with a venue-wide monotonic sequence.
        assertEquals("AAPL", published.get(0).symbol());
        assertEquals(1, published.get(0).sequence());
        assertEquals("MSFT", published.get(1).symbol());
        assertEquals(2, published.get(1).sequence());
        assertEquals(clock.instant(), published.get(0).timestamp());

        assertEquals(1, service.latestSnapshot("AAPL").orElseThrow().buyOrders().size());
        assertEquals(1, service.latestSnapshot("MSFT").orElseThrow().sellOrders().size());
    }

    @Test
    void aSnapshotCycleResetsTheIntervalTimer() {
        registry.bookFor("AAPL");
        clock.advance(INTERVAL);
        service.tick();
        assertEquals(1, published.size());

        service.tick(); // no time has passed since the cycle
        assertEquals(1, published.size());

        clock.advance(INTERVAL);
        service.tick();
        assertEquals(2, published.size());
    }

    @Test
    void snapshotNowForcesACycleRegardlessOfInterval() {
        registry.bookFor("AAPL").addOrder(buy("1", "100", at(0)));

        List<OrderBookSnapshot> cycle = service.snapshotNow();

        assertEquals(1, cycle.size());
        assertEquals(1, published.size());
        assertTrue(service.latestSnapshot("AAPL").isPresent());
    }

    @Test
    void snapshotIsUnaffectedByLaterBookMutations() {
        registry.bookFor("AAPL").addOrder(buy("1", "100", at(0)));
        OrderBookSnapshot snapshot = service.snapshotNow().getFirst();

        registry.bookFor("AAPL").addOrder(buy("5", "101", at(1))); // mutate after capture

        assertEquals(1, snapshot.buyOrders().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.buyOrders().add(buy("1", "1", at(2))));
    }

    @Test
    void rejectsANegativeInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderBookSnapshotService(registry, Duration.ofSeconds(-1), clock));
    }
}
