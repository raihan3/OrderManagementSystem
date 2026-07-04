package com.oms.model;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of matching a single incoming order.
 *
 * @param trades    executions generated, in the order they occurred (empty if nothing crossed)
 * @param remainder the unfilled portion of the incoming order, or empty if it was fully filled. It
 *                  is the {@link OrderStatus#PENDING} quantity now resting on the book (a market
 *                  order rests at the top of its side, ahead of limit orders).
 */
public record MatchResult(List<Trade> trades, Optional<Order> remainder) {

    public MatchResult {
        trades = List.copyOf(trades);
    }

    /**
     * @return {@code true} if the incoming order was fully filled, leaving no remainder
     */
    public boolean fullyFilled() {
        return remainder.isEmpty();
    }

    /**
     * @return {@code true} if there is a remainder that is now resting on the book (a partially
     *         filled limit order)
     */
    public boolean hasRestingRemainder() {
        return remainder.filter(o -> o.status() == OrderStatus.PENDING).isPresent();
    }
}
