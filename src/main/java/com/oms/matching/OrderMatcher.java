package com.oms.matching;

import com.oms.book.OrderBook;
import com.oms.model.MatchResult;
import com.oms.model.Order;

/**
 * Strategy for matching a single incoming order against an {@link OrderBook}.
 *
 * <p>Implementations differ in how an order crosses the book and what becomes of any unfilled
 * quantity (for example, limit orders rest while market orders are cancelled). Obtain the right
 * implementation for a given order from {@link OrderMatcherFactory}.
 */
@FunctionalInterface
public interface OrderMatcher {

    /**
     * Matches the incoming order against the book, mutating the book as fills occur.
     *
     * @param incoming the order to match; must not already be resting on the book
     * @param book     the book to match against
     * @return the executions produced and any unfilled remainder
     */
    MatchResult match(Order incoming, OrderBook book);
}
