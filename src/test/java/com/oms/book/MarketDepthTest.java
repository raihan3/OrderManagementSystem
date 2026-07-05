package com.oms.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.oms.support.TestOrders.at;
import static com.oms.support.TestOrders.buy;
import static com.oms.support.TestOrders.marketBuy;
import static com.oms.support.TestOrders.sell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDepthTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new PriceTimePriorityOrderBook();
    }

    private static void assertLevel(PriceLevel level, String price, String quantity, int orderCount) {
        assertEquals(0, level.price().compareTo(new BigDecimal(price)), "level price");
        assertEquals(0, level.quantity().compareTo(new BigDecimal(quantity)), "level quantity");
        assertEquals(orderCount, level.orderCount(), "level order count");
    }

    @Test
    void aggregatesSamePricedOrdersIntoOneLevel() {
        book.addOrder(buy("3", "100", at(0)));
        book.addOrder(buy("4", "100", at(1)));
        book.addOrder(buy("5", "99", at(2)));

        List<PriceLevel> bids = book.depth(10).bids();

        assertEquals(2, bids.size());
        assertLevel(bids.get(0), "100", "7", 2);
        assertLevel(bids.get(1), "99", "5", 1);
    }

    @Test
    void bidsDescendAndAsksAscendFromBestLevel() {
        book.addOrder(buy("1", "99", at(0)));
        book.addOrder(buy("1", "100", at(1)));
        book.addOrder(sell("1", "102", at(2)));
        book.addOrder(sell("1", "101", at(3)));

        MarketDepth depth = book.depth(10);

        assertEquals(0, depth.bids().get(0).price().compareTo(new BigDecimal("100")));
        assertEquals(0, depth.bids().get(1).price().compareTo(new BigDecimal("99")));
        assertEquals(0, depth.asks().get(0).price().compareTo(new BigDecimal("101")));
        assertEquals(0, depth.asks().get(1).price().compareTo(new BigDecimal("102")));
    }

    @Test
    void truncatesToTheRequestedNumberOfLevels() {
        book.addOrder(sell("1", "101", at(0)));
        book.addOrder(sell("2", "102", at(1)));
        book.addOrder(sell("3", "103", at(2)));

        List<PriceLevel> asks = book.depth(2).asks();

        assertEquals(2, asks.size());
        assertLevel(asks.get(0), "101", "1", 1);
        assertLevel(asks.get(1), "102", "2", 1);
    }

    @Test
    void groupsScaleVariantsOfTheSamePriceIntoOneLevel() {
        book.addOrder(buy("1", "100", at(0)));
        book.addOrder(buy("2", "100.00", at(1)));

        List<PriceLevel> bids = book.depth(10).bids();

        assertEquals(1, bids.size());
        assertLevel(bids.getFirst(), "100", "3", 2);
    }

    @Test
    void excludesRestingMarketOrdersFromTheLadder() {
        book.addOrder(marketBuy("9", at(0))); // no price: cannot form a level
        book.addOrder(buy("3", "100", at(1)));

        List<PriceLevel> bids = book.depth(10).bids();

        assertEquals(1, bids.size());
        assertLevel(bids.getFirst(), "100", "3", 1);
    }

    @Test
    void emptyBookYieldsEmptyDepth() {
        MarketDepth depth = book.depth(5);

        assertTrue(depth.bids().isEmpty());
        assertTrue(depth.asks().isEmpty());
    }

    @Test
    void depthViewIsImmutable() {
        book.addOrder(buy("1", "100", at(0)));
        MarketDepth depth = book.depth(5);

        assertThrows(UnsupportedOperationException.class,
                () -> depth.bids().add(new PriceLevel(BigDecimal.ONE, BigDecimal.ONE, 1)));
    }

    @Test
    void rejectsANonPositiveLevelCount() {
        assertThrows(IllegalArgumentException.class, () -> book.depth(0));
        assertThrows(IllegalArgumentException.class, () -> book.depth(-3));
    }
}
