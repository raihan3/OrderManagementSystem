package com.oms.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeTest {

    @Test
    void exposesAllFields() {
        UUID buyId = UUID.randomUUID();
        UUID sellId = UUID.randomUUID();
        Instant ts = Instant.parse("2026-07-04T10:00:00Z");
        Trade trade = new Trade(buyId, sellId, "AAPL", new BigDecimal("100"), new BigDecimal("5"), ts);

        assertEquals(buyId, trade.buyOrderId());
        assertEquals(sellId, trade.sellOrderId());
        assertEquals("AAPL", trade.symbol());
        assertEquals(new BigDecimal("100"), trade.price());
        assertEquals(new BigDecimal("5"), trade.quantity());
        assertEquals(ts, trade.timestamp());
    }
}
