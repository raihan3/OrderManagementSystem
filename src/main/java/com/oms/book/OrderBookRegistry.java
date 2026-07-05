package com.oms.book;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Routes each symbol to its own {@link OrderBook}. Matching never crosses symbols, so every
 * instrument has an independent book; the registry is the routing layer that keeps them separate.
 *
 * <p>Implementations choose the policy for a symbol that has no book yet: create one on demand, or
 * reject the lookup unless the symbol was {@link #register(String) registered} up front (mirroring
 * how venues load a fixed set of listed instruments from reference data).
 */
public interface OrderBookRegistry {

    /**
     * Ensures a book exists for the symbol, creating an empty one if necessary. Idempotent:
     * calling it again for the same symbol returns the existing book.
     *
     * @return the book for the symbol
     */
    OrderBook register(String symbol);

    /**
     * Returns the book an order for this symbol should be matched against.
     *
     * @throws NoSuchElementException if the symbol has no book and this registry does not create
     *                                books on demand
     */
    OrderBook bookFor(String symbol);

    /**
     * @return the book for the symbol if one exists, without creating it
     */
    Optional<OrderBook> bookIfPresent(String symbol);

    /**
     * @return a read-only view of the symbols that currently have a book
     */
    Set<String> symbols();
}
