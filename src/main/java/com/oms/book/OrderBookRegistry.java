package com.oms.book;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Holds one {@link OrderBook} per symbol and routes lookups to the correct book.
 *
 * <p>Matching never crosses symbols, so each instrument has its own independent book; this registry
 * is the routing layer that keeps them separate. It supports two policies for an order whose symbol
 * has no book yet:
 * <ul>
 *   <li><b>create-on-demand</b> (the default) — a book is created lazily on first use. Convenient
 *       for simulations and tests.</li>
 *   <li><b>strict</b> — only symbols {@link #register(String) registered} up front are tradable, and
 *       {@link #bookFor(String)} rejects anything else. This mirrors how venues load a fixed set of
 *       listed instruments from reference data and refuse orders for unknown symbols.</li>
 * </ul>
 *
 * <p>This class is not thread-safe: consistent with the rest of the engine, a single matching
 * thread is expected to own a registry (concurrency is achieved by sharding symbols across engines,
 * not by sharing one book across threads).
 */
public class OrderBookRegistry {

    private final Map<String, OrderBook> booksBySymbol = new HashMap<>();
    private final boolean createOnDemand;

    /**
     * Creates a registry that lazily creates a book the first time a symbol is seen.
     */
    public OrderBookRegistry() {
        this(true);
    }

    /**
     * @param createOnDemand {@code true} to create books lazily; {@code false} to require every
     *                       symbol to be {@link #register(String) registered} first
     */
    public OrderBookRegistry(boolean createOnDemand) {
        this.createOnDemand = createOnDemand;
    }

    /**
     * Ensures a book exists for the symbol, creating an empty one if necessary. Idempotent: calling
     * it again for the same symbol returns the existing book.
     *
     * @return the book for the symbol
     */
    public OrderBook register(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return booksBySymbol.computeIfAbsent(symbol, s -> new OrderBook());
    }

    /**
     * Returns the book an order for this symbol should be matched against.
     *
     * @throws NoSuchElementException if the symbol has no book and this registry is strict
     *                                (not create-on-demand)
     */
    public OrderBook bookFor(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        if (createOnDemand) {
            return booksBySymbol.computeIfAbsent(symbol, s -> new OrderBook());
        }
        OrderBook book = booksBySymbol.get(symbol);
        if (book == null) {
            throw new NoSuchElementException("no order book registered for symbol '" + symbol + "'");
        }
        return book;
    }

    /**
     * @return the book for the symbol if one exists, without creating it
     */
    public Optional<OrderBook> bookIfPresent(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return Optional.ofNullable(booksBySymbol.get(symbol));
    }

    /**
     * @return a read-only view of the symbols that currently have a book
     */
    public Set<String> symbols() {
        return Collections.unmodifiableSet(booksBySymbol.keySet());
    }
}
