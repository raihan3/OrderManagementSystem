package com.oms.engine;

import com.oms.model.MatchResult;
import com.oms.model.Order;

/**
 * The core domain operation of the order management system: submit an order, get back the
 * executions it produced and whatever became of its unfilled quantity.
 *
 * <p>Implementations own all routing and lifecycle mechanics behind this single entry point —
 * per-symbol book selection, order-type matching semantics, stop parking/triggering, market-data
 * side effects. See {@link OrderMatchingService} for the standard implementation.
 */
public interface MatchingEngine {

    /**
     * Processes one incoming order to completion.
     *
     * @param incoming the order to process; must not already be resting on a book
     * @return the executions produced and any unfilled remainder
     */
    MatchResult match(Order incoming);
}
