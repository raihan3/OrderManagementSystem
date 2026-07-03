package com.oms.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable record of a single buy or sell order submitted to the order management system.
 *
 * @param id        unique identifier of the order
 * @param symbol    ticker/commodity identifying the instrument being traded
 * @param side      whether the order is a {@link Side#BUY} or {@link Side#SELL}
 * @param quantity  amount of the underlying instrument to be traded
 * @param price     price per unit at which the order was placed
 * @param timestamp instant the order was received by the order management system
 * @param status    current lifecycle state of the order
 */
public record Order(
        UUID id,
        String symbol,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        Instant timestamp,
        OrderStatus status
) {

    /**
     * Validates every field. Runs for all construction paths (direct, {@link Builder}, and
     * order amendments), so an invalid {@code Order} can never exist.
     *
     * @throws NullPointerException     if any field is {@code null}
     * @throws IllegalArgumentException if {@code symbol} is blank, or {@code quantity} or
     *                                  {@code price} is not strictly positive
     */
    public Order {

        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(status, "status must not be null");

        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be strictly positive, but was " + quantity);
        }
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be strictly positive, but was " + price);
        }
    }

    /**
     * @return a new, empty builder for constructing an {@link Order} from scratch
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return a builder pre-populated with this order's fields, for producing an amended copy
     */
    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .symbol(symbol)
                .side(side)
                .quantity(quantity)
                .price(price)
                .timestamp(timestamp)
                .status(status);
    }

    /**
     * Fluent builder for {@link Order}. Because {@code Order} is immutable, this is also the
     * mechanism for "editing" an order: start from {@link #toBuilder()}, change the desired
     * fields, and {@link #build()} a new instance.
     */
    public static final class Builder {

        private UUID id;
        private String symbol;
        private Side side;
        private BigDecimal quantity;
        private BigDecimal price;
        private Instant timestamp;
        private OrderStatus status;

        private Builder() {
        }

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder symbol(String symbol) {
            this.symbol = symbol;
            return this;
        }

        public Builder side(Side side) {
            this.side = side;
            return this;
        }

        public Builder quantity(BigDecimal quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder price(BigDecimal price) {
            this.price = price;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder status(OrderStatus status) {
            this.status = status;
            return this;
        }

        public Order build() {
            return new Order(id, symbol, side, quantity, price, timestamp, status);
        }
    }
}
