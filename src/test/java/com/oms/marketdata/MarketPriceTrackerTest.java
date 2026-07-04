package com.oms.marketdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketPriceTrackerTest {

    private MarketPriceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new MarketPriceTracker();
    }

    @Test
    void unknownSymbolHasNoPrice() {
        assertEquals(Optional.empty(), tracker.lastPrice("AAPL"));
    }

    @Test
    void recordsAndReturnsLatestPrice() {
        tracker.record("AAPL", new BigDecimal("100"));
        assertEquals(0, tracker.lastPrice("AAPL").orElseThrow().compareTo(new BigDecimal("100")));
    }

    @Test
    void latestRecordWins() {
        tracker.record("AAPL", new BigDecimal("100"));
        tracker.record("AAPL", new BigDecimal("101"));
        assertEquals(0, tracker.lastPrice("AAPL").orElseThrow().compareTo(new BigDecimal("101")));
    }

    @Test
    void tracksSymbolsIndependently() {
        tracker.record("AAPL", new BigDecimal("100"));
        tracker.record("MSFT", new BigDecimal("250"));

        assertEquals(0, tracker.lastPrice("AAPL").orElseThrow().compareTo(new BigDecimal("100")));
        assertEquals(0, tracker.lastPrice("MSFT").orElseThrow().compareTo(new BigDecimal("250")));
    }

    @Test
    void rejectsNulls() {
        assertThrows(NullPointerException.class, () -> tracker.record(null, BigDecimal.ONE));
        assertThrows(NullPointerException.class, () -> tracker.record("AAPL", null));
        assertThrows(NullPointerException.class, () -> tracker.lastPrice(null));
    }
}
