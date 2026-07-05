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
 * @param type      the pricing and time-in-force behaviour of the order (see {@link OrderType})
 * @param quantity  amount of the underlying instrument to be traded
 * @param price     limit price per unit for a priced ({@link OrderType#LIMIT},
 *                  {@link OrderType#IOC}, {@link OrderType#FOK} or {@link OrderType#STOP_LIMIT})
 *                  order; {@code null} for {@link OrderType#MARKET} and {@link OrderType#STOP}
 *                  orders, which carry no limit price
 * @param stopPrice trigger price for a {@link OrderType#STOP} or {@link OrderType#STOP_LIMIT}
 *                  order — the order lies dormant until the market trades at or through it;
 *                  {@code null} for every other type
 * @param timestamp instant the order was received by the order management system
 * @param status    current lifecycle state of the order
 */
public record Order(
        UUID id,
        String symbol,
        Side side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal stopPrice,
        Instant timestamp,
        OrderStatus status
) {

    /**
     * Validates every field. Runs for all construction paths (direct, {@link Builder}, and
     * order amendments), so an invalid {@code Order} can never exist.
     *
     * @throws NullPointerException     if a required field is {@code null} (which fields are
     *                                  required depends on {@code type})
     * @throws IllegalArgumentException if {@code symbol} is blank, {@code quantity} is not
     *                                  strictly positive, a required price or stop price is not
     *                                  strictly positive, or the order carries a price the type
     *                                  forbids (a limit price on a {@link OrderType#MARKET} or
     *                                  {@link OrderType#STOP} order, or a stop price on a
     *                                  non-stop order)
     */
    public Order {

        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(status, "status must not be null");

        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be strictly positive, but was " + quantity);
        }

        switch (type) {
            case LIMIT, IOC, FOK -> {
                requirePositivePrice(price, type);
                requireNoStopPrice(stopPrice, type);
            }
            case MARKET -> {
                requireNoLimitPrice(price, type);
                requireNoStopPrice(stopPrice, type);
            }
            case STOP -> {
                requireNoLimitPrice(price, type);
                requirePositiveStopPrice(stopPrice, type);
            }
            case STOP_LIMIT -> {
                requirePositivePrice(price, type);
                requirePositiveStopPrice(stopPrice, type);
            }
        }
    }

    private static void requirePositivePrice(BigDecimal price, OrderType type) {
        Objects.requireNonNull(price, "price must not be null for a " + type + " order");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be strictly positive, but was " + price);
        }
    }

    private static void requireNoLimitPrice(BigDecimal price, OrderType type) {
        if (price != null) {
            throw new IllegalArgumentException("a " + type + " order must not carry a limit price, but was " + price);
        }
    }

    private static void requirePositiveStopPrice(BigDecimal stopPrice, OrderType type) {
        Objects.requireNonNull(stopPrice, "stopPrice must not be null for a " + type + " order");
        if (stopPrice.signum() <= 0) {
            throw new IllegalArgumentException("stopPrice must be strictly positive, but was " + stopPrice);
        }
    }

    private static void requireNoStopPrice(BigDecimal stopPrice, OrderType type) {
        if (stopPrice != null) {
            throw new IllegalArgumentException("a " + type + " order must not carry a stop price, but was " + stopPrice);
        }
    }

    /**
     * @return {@code true} if this is a {@link OrderType#MARKET} order
     */
    public boolean isMarket() {
        return type == OrderType.MARKET;
    }

    /**
     * @return {@code true} if this is a trigger-based ({@link OrderType#STOP} or
     *         {@link OrderType#STOP_LIMIT}) order
     */
    public boolean isStop() {
        return type == OrderType.STOP || type == OrderType.STOP_LIMIT;
    }

    /**
     * Evaluates this stop order's trigger against a traded price: a buy stop triggers when the
     * market trades at or above its stop price, a sell stop when the market trades at or below it.
     *
     * @param lastTradePrice the price the market just traded at
     * @return whether that trade fires this stop's trigger
     * @throws IllegalStateException if this is not a stop order
     */
    public boolean isStopTriggeredAt(BigDecimal lastTradePrice) {
        Objects.requireNonNull(lastTradePrice, "lastTradePrice must not be null");
        if (!isStop()) {
            throw new IllegalStateException("order " + id + " is a " + type + " order and has no stop trigger");
        }
        return side == Side.BUY
                ? lastTradePrice.compareTo(stopPrice) >= 0
                : lastTradePrice.compareTo(stopPrice) <= 0;
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
                .type(type)
                .quantity(quantity)
                .price(price)
                .stopPrice(stopPrice)
                .timestamp(timestamp)
                .status(status);
    }

    /**
     * Fluent builder for {@link Order}. Because {@code Order} is immutable, this is also the
     * mechanism for "editing" an order: start from {@link #toBuilder()}, change the desired
     * fields, and {@link #build()} a new instance.
     *
     * <p>The {@link #type(OrderType)} defaults to {@link OrderType#LIMIT}.
     */
    public static final class Builder {

        private UUID id;
        private String symbol;
        private Side side;
        private OrderType type = OrderType.LIMIT;
        private BigDecimal quantity;
        private BigDecimal price;
        private BigDecimal stopPrice;
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

        public Builder type(OrderType type) {
            this.type = type;
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

        public Builder stopPrice(BigDecimal stopPrice) {
            this.stopPrice = stopPrice;
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
            return new Order(id, symbol, side, type, quantity, price, stopPrice, timestamp, status);
        }
    }
}
