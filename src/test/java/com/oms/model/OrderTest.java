package com.oms.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.oms.support.TestOrders.base;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {

    @Test
    void builderProducesOrderWithAllFields() {
        UUID id = UUID.randomUUID();
        Instant ts = Instant.parse("2026-07-04T12:00:00Z");
        Order order = Order.builder()
                .id(id)
                .symbol("MSFT")
                .side(Side.SELL)
                .quantity(new BigDecimal("10"))
                .price(new BigDecimal("250.50"))
                .timestamp(ts)
                .status(OrderStatus.PENDING)
                .build();

        assertEquals(id, order.id());
        assertEquals("MSFT", order.symbol());
        assertEquals(Side.SELL, order.side());
        assertEquals(new BigDecimal("10"), order.quantity());
        assertEquals(new BigDecimal("250.50"), order.price());
        assertEquals(ts, order.timestamp());
        assertEquals(OrderStatus.PENDING, order.status());
    }

    @Test
    void toBuilderRoundTripsToAnEqualOrder() {
        Order original = base().build();
        Order copy = original.toBuilder().build();

        assertEquals(original, copy);
        assertNotSame(original, copy);
    }

    @Test
    void toBuilderChangesOnlyTheAmendedField() {
        Order original = base().price(new BigDecimal("10")).build();
        Order amended = original.toBuilder().price(new BigDecimal("42")).build();

        assertEquals(new BigDecimal("42"), amended.price());
        assertEquals(original.id(), amended.id());
        assertEquals(original.quantity(), amended.quantity());
        assertEquals(original.timestamp(), amended.timestamp());
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class, () -> base().id(null).build());
        assertThrows(NullPointerException.class, () -> base().symbol(null).build());
        assertThrows(NullPointerException.class, () -> base().side(null).build());
        assertThrows(NullPointerException.class, () -> base().quantity(null).build());
        assertThrows(NullPointerException.class, () -> base().price(null).build());
        assertThrows(NullPointerException.class, () -> base().timestamp(null).build());
        assertThrows(NullPointerException.class, () -> base().status(null).build());
    }

    @Test
    void rejectsBlankSymbol() {
        assertThrows(IllegalArgumentException.class, () -> base().symbol("").build());
        assertThrows(IllegalArgumentException.class, () -> base().symbol("   ").build());
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> base().quantity(BigDecimal.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> base().quantity(new BigDecimal("-1")).build());
    }

    @Test
    void rejectsNonPositivePrice() {
        assertThrows(IllegalArgumentException.class, () -> base().price(BigDecimal.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> base().price(new BigDecimal("-0.01")).build());
    }
}
