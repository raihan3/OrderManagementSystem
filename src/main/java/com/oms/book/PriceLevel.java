package com.oms.book;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One aggregated price level in a market-depth view: every resting order at the same price on one
 * side of the book, rolled up into a total size.
 *
 * @param price      the level's price
 * @param quantity   total resting quantity across all orders at this price
 * @param orderCount how many resting orders make up the level
 */
public record PriceLevel(BigDecimal price, BigDecimal quantity, int orderCount) {

    public PriceLevel {
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be strictly positive, but was " + quantity);
        }
        if (orderCount < 1) {
            throw new IllegalArgumentException("orderCount must be at least 1, but was " + orderCount);
        }
    }
}
