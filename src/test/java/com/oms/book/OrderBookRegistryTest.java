package com.oms.book;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderBookRegistryTest {

    @Test
    void createsABookOnDemandAndReusesIt() {
        OrderBookRegistry registry = new InMemoryOrderBookRegistry();

        OrderBook first = registry.bookFor("AAPL");
        assertSame(first, registry.bookFor("AAPL"));
    }

    @Test
    void differentSymbolsGetDistinctBooks() {
        OrderBookRegistry registry = new InMemoryOrderBookRegistry();

        assertNotSame(registry.bookFor("AAPL"), registry.bookFor("MSFT"));
    }

    @Test
    void strictRegistryRejectsUnregisteredSymbol() {
        OrderBookRegistry registry = new InMemoryOrderBookRegistry(false);

        assertThrows(NoSuchElementException.class, () -> registry.bookFor("AAPL"));
    }

    @Test
    void strictRegistryServesRegisteredSymbol() {
        OrderBookRegistry registry = new InMemoryOrderBookRegistry(false);

        OrderBook registered = registry.register("AAPL");
        assertSame(registered, registry.bookFor("AAPL"));
    }

    @Test
    void registerIsIdempotent() {
        OrderBookRegistry registry = new InMemoryOrderBookRegistry();

        assertSame(registry.register("AAPL"), registry.register("AAPL"));
    }

    @Test
    void bookIfPresentDoesNotCreate() {
        OrderBookRegistry registry = new InMemoryOrderBookRegistry();

        assertEquals(Optional.empty(), registry.bookIfPresent("AAPL"));
        assertEquals(Set.of(), registry.symbols());

        registry.register("AAPL");
        assertEquals(Optional.of(registry.bookFor("AAPL")), registry.bookIfPresent("AAPL"));
        assertEquals(Set.of("AAPL"), registry.symbols());
    }

    @Test
    void rejectsNullSymbol() {
        OrderBookRegistry registry = new InMemoryOrderBookRegistry();
        assertThrows(NullPointerException.class, () -> registry.bookFor(null));
        assertThrows(NullPointerException.class, () -> registry.register(null));
        assertThrows(NullPointerException.class, () -> registry.bookIfPresent(null));
    }
}
