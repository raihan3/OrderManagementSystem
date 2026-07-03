package com.oms.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single execution produced when an incoming order crosses with a resting order.
 *
 * @param buyOrderId  id of the order on the buy side of the execution
 * @param sellOrderId id of the order on the sell side of the execution
 * @param symbol      instrument that was traded
 * @param price       price at which the execution occurred (the resting/maker order's price)
 * @param quantity    quantity that changed hands
 * @param timestamp   instant the execution occurred
 */
public record Trade(
        UUID buyOrderId,
        UUID sellOrderId,
        String symbol,
        BigDecimal price,
        BigDecimal quantity,
        Instant timestamp
) {
}
