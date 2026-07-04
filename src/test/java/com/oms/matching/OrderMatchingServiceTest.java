package com.oms.matching;

import com.oms.book.OrderBook;
import com.oms.model.Order;
import com.oms.model.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.oms.support.TestOrders.at;
import static com.oms.support.TestOrders.buy;
import static com.oms.support.TestOrders.sell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderMatchingServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-04T15:30:00Z");

    private OrderBook book;
    private OrderMatchingService service;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
        service = new OrderMatchingService(book, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void nonCrossingOrderRestsWithoutTrading() {
        book.addOrder(sell("5", "100", at(0)));

        Order incoming = buy("5", "99", at(1));
        MatchResult result = service.match(incoming);

        assertTrue(result.trades().isEmpty());
        assertFalse(result.fullyFilled());
        assertEquals(incoming.id(), book.bestBuy().orElseThrow().id());
    }

    @Test
    void orderIntoEmptyBookRestsFully() {
        MatchResult result = service.match(buy("5", "100", at(0)));

        assertTrue(result.trades().isEmpty());
        assertFalse(result.fullyFilled());
    }

    @Test
    void fullyFillingIncomingConsumesRestingOrder() {
        book.addOrder(sell("5", "100", at(0)));

        MatchResult result = service.match(buy("5", "100", at(1)));

        assertEquals(1, result.trades().size());
        assertTrue(result.fullyFilled());
        assertTrue(book.bestSell().isEmpty());
        assertEquals(0, result.trades().getFirst().quantity().compareTo(new BigDecimal("5")));
    }

    @Test
    void partiallyFillsRestingOrderAndLeavesRemainderOnBook() {
        book.addOrder(sell("10", "100", at(0)));

        MatchResult result = service.match(buy("4", "100", at(1)));

        assertEquals(1, result.trades().size());
        assertTrue(result.fullyFilled());
        assertEquals(0, book.bestSell().orElseThrow().quantity().compareTo(new BigDecimal("6")));
    }

    @Test
    void restsRemainderWhenIncomingExceedsBookLiquidity() {
        book.addOrder(sell("3", "100", at(0)));

        Order incoming = buy("5", "100", at(1));
        MatchResult result = service.match(incoming);

        assertEquals(1, result.trades().size());
        assertFalse(result.fullyFilled());
        Order rested = book.bestBuy().orElseThrow();
        assertEquals(incoming.id(), rested.id());
        assertEquals(0, rested.quantity().compareTo(new BigDecimal("2")));
    }

    @Test
    void matchesInPriceThenTimePriority() {
        Order sellA = sell("5", "100", at(0)); // best: earliest at 100
        Order sellB = sell("5", "100", at(1));
        Order sellC = sell("5", "101", at(2)); // worse price, untouched
        book.addOrder(sellC);
        book.addOrder(sellB);
        book.addOrder(sellA);

        MatchResult result = service.match(buy("8", "100.5", at(3)));

        List<Trade> trades = result.trades();
        assertEquals(2, trades.size());
        assertEquals(sellA.id(), trades.get(0).sellOrderId());
        assertEquals(0, trades.get(0).quantity().compareTo(new BigDecimal("5")));
        assertEquals(sellB.id(), trades.get(1).sellOrderId());
        assertEquals(0, trades.get(1).quantity().compareTo(new BigDecimal("3")));

        // sellB half-consumed and now best; sellC never touched.
        Order bestSell = book.bestSell().orElseThrow();
        assertEquals(sellB.id(), bestSell.id());
        assertEquals(0, bestSell.quantity().compareTo(new BigDecimal("2")));
    }

    @Test
    void executesAtRestingMakerPrice() {
        book.addOrder(sell("5", "100", at(0)));

        MatchResult result = service.match(buy("5", "105", at(1)));

        assertEquals(0, result.trades().getFirst().price().compareTo(new BigDecimal("100")));
    }

    @Test
    void incomingSellMatchesRestingBuyAndAssignsSidesCorrectly() {
        Order restingBuy = buy("5", "100", at(0));
        book.addOrder(restingBuy);

        Order incomingSell = sell("5", "100", at(1));
        MatchResult result = service.match(incomingSell);

        Trade trade = result.trades().getFirst();
        assertEquals(restingBuy.id(), trade.buyOrderId());
        assertEquals(incomingSell.id(), trade.sellOrderId());
    }

    @Test
    void tradeCarriesSymbolAndClockTimestamp() {
        book.addOrder(sell("5", "100", at(0)));

        Trade trade = service.match(buy("5", "100", at(1))).trades().getFirst();

        assertEquals("AAPL", trade.symbol());
        assertEquals(NOW, trade.timestamp());
    }

    @Test
    void defaultConstructorProvidesOwnBook() {
        OrderMatchingService standalone = new OrderMatchingService();
        assertSame(standalone.orderBook(), standalone.orderBook());
        assertTrue(standalone.orderBook().bestBuy().isEmpty());
    }

    @Test
    void rejectsNullIncomingOrder() {
        assertThrows(NullPointerException.class, () -> service.match(null));
    }
}
