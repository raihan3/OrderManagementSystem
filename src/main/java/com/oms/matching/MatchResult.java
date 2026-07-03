package com.oms.matching;

import com.oms.model.Order;
import com.oms.model.Trade;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of matching a single incoming order.
 *
 * @param trades          executions generated, in the order they occurred (empty if nothing crossed)
 * @param restingRemainder the unfilled portion of the incoming order that was placed on the book,
 *                         or empty if the incoming order was fully filled
 */
public record MatchResult(List<Trade> trades, Optional<Order> restingRemainder) {

    public MatchResult {
        trades = List.copyOf(trades);
    }

    /**
     * @return {@code true} if the incoming order was fully filled and nothing rested on the book
     */
    public boolean fullyFilled() {
        return restingRemainder.isEmpty();
    }
}
