package com.oms.matching;

import com.oms.model.Order;
import com.oms.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.oms.support.TestOrders.base;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchResultTest {

    private static Trade aTrade() {
        return new Trade(UUID.randomUUID(), UUID.randomUUID(), "AAPL",
                new BigDecimal("100"), new BigDecimal("1"), Instant.parse("2026-07-04T10:00:00Z"));
    }

    @Test
    void fullyFilledWhenNoRemainder() {
        MatchResult result = new MatchResult(List.of(aTrade()), Optional.empty());
        assertTrue(result.fullyFilled());
    }

    @Test
    void notFullyFilledWhenRemainderRests() {
        Order remainder = base().build();
        MatchResult result = new MatchResult(List.of(), Optional.of(remainder));
        assertFalse(result.fullyFilled());
    }

    @Test
    void tradesAreDefensivelyCopied() {
        List<Trade> source = new ArrayList<>();
        source.add(aTrade());
        MatchResult result = new MatchResult(source, Optional.empty());

        source.add(aTrade()); // mutating the source must not leak into the result
        assertEquals(1, result.trades().size());
    }

    @Test
    void tradesViewIsUnmodifiable() {
        MatchResult result = new MatchResult(List.of(aTrade()), Optional.empty());
        assertThrows(UnsupportedOperationException.class, () -> result.trades().add(aTrade()));
    }
}
