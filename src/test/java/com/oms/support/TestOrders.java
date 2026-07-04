package com.oms.support;

import com.oms.model.Order;
import com.oms.model.OrderStatus;
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
        return base().side(Side.BUY).quantity(bd(quantity)).price(bd(price)).timestamp(timestamp).build();
    }

    public static Order sell(String quantity, String price, Instant timestamp) {
        return base().side(Side.SELL).quantity(bd(quantity)).price(bd(price)).timestamp(timestamp).build();
    }

    /** {@code T0} plus the given number of seconds — handy for ordering by timestamp. */
    public static Instant at(long plusSeconds) {
        return T0.plusSeconds(plusSeconds);
    }

    public static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
