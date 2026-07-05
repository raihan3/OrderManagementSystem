package com.oms.support;

import com.oms.model.Order;
import com.oms.model.OrderStatus;
import com.oms.model.OrderType;
import com.oms.model.Side;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared factory helpers for building {@link Order}s in tests, so each test only spells out the
 * fields it actually cares about.
 */
public final class TestOrders {

    /** A fixed reference instant so timestamp-priority tests are deterministic. */
    public static final Instant T0 = Instant.parse("2026-07-04T10:00:00Z");

    public static final String SYMBOL = "AAPL";

    private TestOrders() {
    }

    /** A builder pre-populated with valid defaults; override only what a test needs. */
    public static Order.Builder base() {
        return Order.builder()
                .id(UUID.randomUUID())
                .symbol(SYMBOL)
                .side(Side.BUY)
                .quantity(BigDecimal.ONE)
                .price(BigDecimal.TEN)
                .timestamp(T0)
                .status(OrderStatus.PENDING);
    }

    public static Order buy(String quantity, String price, Instant timestamp) {
        return buy(SYMBOL, quantity, price, timestamp);
    }

    public static Order sell(String quantity, String price, Instant timestamp) {
        return sell(SYMBOL, quantity, price, timestamp);
    }

    public static Order buy(String symbol, String quantity, String price, Instant timestamp) {
        return base().symbol(symbol).side(Side.BUY).quantity(bd(quantity)).price(bd(price)).timestamp(timestamp).build();
    }

    public static Order sell(String symbol, String quantity, String price, Instant timestamp) {
        return base().symbol(symbol).side(Side.SELL).quantity(bd(quantity)).price(bd(price)).timestamp(timestamp).build();
    }

    public static Order marketBuy(String quantity, Instant timestamp) {
        return market(Side.BUY, quantity, timestamp);
    }

    public static Order marketSell(String quantity, Instant timestamp) {
        return market(Side.SELL, quantity, timestamp);
    }

    private static Order market(Side side, String quantity, Instant timestamp) {
        return base()
                .side(side)
                .type(OrderType.MARKET)
                .price(null) // base() sets a default price; a market order must not carry one
                .quantity(bd(quantity))
                .timestamp(timestamp)
                .build();
    }

    public static Order iocBuy(String quantity, String price, Instant timestamp) {
        return ioc(Side.BUY, quantity, price, timestamp);
    }

    public static Order iocSell(String quantity, String price, Instant timestamp) {
        return ioc(Side.SELL, quantity, price, timestamp);
    }

    private static Order ioc(Side side, String quantity, String price, Instant timestamp) {
        return base()
                .side(side)
                .type(OrderType.IOC)
                .quantity(bd(quantity))
                .price(bd(price))
                .timestamp(timestamp)
                .build();
    }

    public static Order fokBuy(String quantity, String price, Instant timestamp) {
        return fok(Side.BUY, quantity, price, timestamp);
    }

    public static Order fokSell(String quantity, String price, Instant timestamp) {
        return fok(Side.SELL, quantity, price, timestamp);
    }

    private static Order fok(Side side, String quantity, String price, Instant timestamp) {
        return base()
                .side(side)
                .type(OrderType.FOK)
                .quantity(bd(quantity))
                .price(bd(price))
                .timestamp(timestamp)
                .build();
    }

    public static Order stopBuy(String quantity, String stopPrice, Instant timestamp) {
        return stop(Side.BUY, quantity, stopPrice, timestamp);
    }

    public static Order stopSell(String quantity, String stopPrice, Instant timestamp) {
        return stop(Side.SELL, quantity, stopPrice, timestamp);
    }

    private static Order stop(Side side, String quantity, String stopPrice, Instant timestamp) {
        return base()
                .side(side)
                .type(OrderType.STOP)
                .price(null) // base() sets a default price; a STOP order must not carry one
                .stopPrice(bd(stopPrice))
                .quantity(bd(quantity))
                .timestamp(timestamp)
                .build();
    }

    public static Order stopLimitBuy(String quantity, String stopPrice, String limitPrice, Instant timestamp) {
        return stopLimit(Side.BUY, quantity, stopPrice, limitPrice, timestamp);
    }

    public static Order stopLimitSell(String quantity, String stopPrice, String limitPrice, Instant timestamp) {
        return stopLimit(Side.SELL, quantity, stopPrice, limitPrice, timestamp);
    }

    private static Order stopLimit(Side side, String quantity, String stopPrice, String limitPrice, Instant timestamp) {
        return base()
                .side(side)
                .type(OrderType.STOP_LIMIT)
                .stopPrice(bd(stopPrice))
                .price(bd(limitPrice))
                .quantity(bd(quantity))
                .timestamp(timestamp)
                .build();
    }

    /** {@code T0} plus the given number of seconds — handy for ordering by timestamp. */
    public static Instant at(long plusSeconds) {
        return T0.plusSeconds(plusSeconds);
    }

    public static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
