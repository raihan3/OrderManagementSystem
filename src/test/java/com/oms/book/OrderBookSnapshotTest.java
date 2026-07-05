package com.oms.book;

import com.oms.model.Order;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.oms.support.TestOrders.at;
import static com.oms.support.TestOrders.buy;
import static com.oms.support.TestOrders.marketBuy;
import static com.oms.support.TestOrders.sell;
import static com.oms.support.TestOrders.stopBuy;
import static com.oms.support.TestOrders.stopSell;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderBookSnapshotTest {

    private static final Instant AS_OF = Instant.parse("2026-07-05T10:00:00Z");

    @Test
    void captureCopiesBothSidesInPriorityOrderAndTheStopLists() {
        OrderBook book = new PriceTimePriorityOrderBook();
        Order bestBuy = buy("1", "101", at(0));
        Order worseBuy = buy("1", "100", at(1));
        Order bestSell = sell("1", "102", at(2));
        Order buyStop = stopBuy("1", "105", at(3));
        Order sellStop = stopSell("1", "95", at(4));
        book.addOrder(worseBuy);
        book.addOrder(bestBuy);
        book.addOrder(bestSell);
        book.addStopOrder(buyStop);
        book.addStopOrder(sellStop);

        OrderBookSnapshot snapshot = OrderBookSnapshot.capture("AAPL", book, 7, AS_OF);

        assertEquals("AAPL", snapshot.symbol());
        assertEquals(7, snapshot.sequence());
        assertEquals(AS_OF, snapshot.timestamp());
        assertEquals(List.of(bestBuy, worseBuy), snapshot.buyOrders()); // best first
        assertEquals(List.of(bestSell), snapshot.sellOrders());
        assertEquals(List.of(buyStop), snapshot.buyStopOrders());
        assertEquals(List.of(sellStop), snapshot.sellStopOrders());
    }

    @Test
    void bestAndSpreadReflectTheSnapshotState() {
        OrderBook book = new PriceTimePriorityOrderBook();
        book.addOrder(buy("1", "100", at(0)));
        book.addOrder(sell("1", "105", at(1)));

        OrderBookSnapshot snapshot = OrderBookSnapshot.capture("AAPL", book, 1, AS_OF);

        assertEquals(0, snapshot.bestBuy().orElseThrow().price().compareTo(new BigDecimal("100")));
        assertEquals(0, snapshot.bestSell().orElseThrow().price().compareTo(new BigDecimal("105")));
        assertEquals(0, snapshot.spread().orElseThrow().compareTo(new BigDecimal("5")));
    }

    @Test
    void snapshotDepthMatchesTheLiveBookDepthAtCaptureTime() {
        OrderBook book = new PriceTimePriorityOrderBook();
        book.addOrder(buy("3", "100", at(0)));
        book.addOrder(buy("4", "100", at(1)));
        book.addOrder(sell("2", "105", at(2)));

        OrderBookSnapshot snapshot = OrderBookSnapshot.capture("AAPL", book, 1, AS_OF);
        MarketDepth liveDepth = book.depth(5);

        book.addOrder(buy("9", "102", at(3))); // later mutation must not affect the snapshot view

        assertEquals(liveDepth, snapshot.depth(5));
        assertEquals(1, snapshot.depth(5).bids().size());
        assertEquals(0, snapshot.depth(5).bids().getFirst().quantity().compareTo(new BigDecimal("7")));
    }

    @Test
    void spreadIsEmptyWhenTopOfSnapshotIsAMarketOrder() {
        OrderBook book = new PriceTimePriorityOrderBook();
        book.addOrder(marketBuy("1", at(0)));
        book.addOrder(sell("1", "105", at(1)));

        OrderBookSnapshot snapshot = OrderBookSnapshot.capture("AAPL", book, 1, AS_OF);

        assertEquals(Optional.empty(), snapshot.spread());
    }
}
