package com.oms.book;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * In-memory {@link OrderBookRegistry} backed by a hash map, with a pluggable book factory so the
 * registry does not dictate which {@link OrderBook} implementation a venue runs (defaults to
 * {@link PriceTimePriorityOrderBook}).
 *
 * <p>Supports two policies for an order whose symbol has no book yet:
 * <ul>
 *   <li><b>create-on-demand</b> (the default) — a book is created lazily on first use. Convenient
 *       for simulations and tests.</li>
 *   <li><b>strict</b> — only symbols {@link #register(String) registered} up front are tradable,
 *       and {@link #bookFor(String)} rejects anything else.</li>
 * </ul>
 *
 * <p>Not thread-safe: consistent with the rest of the engine, a single matching thread is expected
 * to own a registry (concurrency is achieved by sharding symbols across engines, not by sharing
 * one book across threads).
 */
public class InMemoryOrderBookRegistry implements OrderBookRegistry {

    private final Map<String, OrderBook> booksBySymbol = new HashMap<>();
    private final boolean createOnDemand;
    private final Supplier<OrderBook> bookFactory;

    /**
     * Creates a registry that lazily creates a {@link PriceTimePriorityOrderBook} the first time a
     * symbol is seen.
     */
    public InMemoryOrderBookRegistry() {
        this(true);
    }

    /**
     * @param createOnDemand {@code true} to create books lazily; {@code false} to require every
     *                       symbol to be {@link #register(String) registered} first
     */
    public InMemoryOrderBookRegistry(boolean createOnDemand) {
        this(createOnDemand, PriceTimePriorityOrderBook::new);
    }

    /**
     * @param createOnDemand {@code true} to create books lazily; {@code false} to require every
     *                       symbol to be {@link #register(String) registered} first
     * @param bookFactory    creates the empty book for each newly seen symbol
     */
    public InMemoryOrderBookRegistry(boolean createOnDemand, Supplier<OrderBook> bookFactory) {
        this.createOnDemand = createOnDemand;
        this.bookFactory = Objects.requireNonNull(bookFactory, "bookFactory must not be null");
    }

    @Override
    public OrderBook register(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return booksBySymbol.computeIfAbsent(symbol, s -> bookFactory.get());
    }

    @Override
    public OrderBook bookFor(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        if (createOnDemand) {
            return register(symbol);
        }
        OrderBook book = booksBySymbol.get(symbol);
        if (book == null) {
            throw new NoSuchElementException("no order book registered for symbol '" + symbol + "'");
        }
        return book;
    }

    @Override
    public Optional<OrderBook> bookIfPresent(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return Optional.ofNullable(booksBySymbol.get(symbol));
    }

    @Override
    public Set<String> symbols() {
        return Collections.unmodifiableSet(booksBySymbol.keySet());
    }
}
