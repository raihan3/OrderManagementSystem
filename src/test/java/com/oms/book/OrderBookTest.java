package com.oms.book;

import com.oms.model.Order;
import com.oms.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.oms.support.TestOrders.at;
import static com.oms.support.TestOrders.base;
import static com.oms.support.TestOrders.buy;
import static com.oms.support.TestOrders.marketBuy;
import static com.oms.support.TestOrders.sell;
import static com.oms.support.TestOrders.stopBuy;
import static com.oms.support.TestOrders.stopSell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
    }

    @Test
    void bestBuyIsHighestPriced() {
        book.addOrder(buy("1", "100", at(0)));
        Order best = buy("1", "101", at(1));
        book.addOrder(best);
        book.addOrder(buy("1", "99", at(2)));

        assertSame(best, book.bestBuy().orElseThrow());
    }

    @Test
    void bestSellIsLowestPriced() {
        book.addOrder(sell("1", "100", at(0)));
        Order best = sell("1", "99", at(1));
        book.addOrder(best);
        book.addOrder(sell("1", "101", at(2)));

        assertSame(best, book.bestSell().orElseThrow());
    }

    @Test
    void buysAtSamePriceBreakTiesByEarliestTimestamp() {
        Order earlier = buy("1", "100", at(0));
        book.addOrder(buy("1", "100", at(5)));
        book.addOrder(earlier);

        assertSame(earlier, book.bestBuy().orElseThrow());
    }

    @Test
    void sellsAtSamePriceBreakTiesByEarliestTimestamp() {
        Order earlier = sell("1", "100", at(0));
        book.addOrder(sell("1", "100", at(5)));
        book.addOrder(earlier);

        assertSame(earlier, book.bestSell().orElseThrow());
    }

    @Test
    void ordersWithIdenticalPriceAndTimestampBothRest() {
        book.addOrder(buy("1", "100", at(0)));
        book.addOrder(buy("1", "100", at(0)));

        assertEquals(2, book.buyOrders().size());
    }

    @Test
    void removeOrderReportsWhetherPresent() {
        Order order = buy("1", "100", at(0));
        book.addOrder(order);

        assertTrue(book.removeOrder(order));
        assertFalse(book.removeOrder(order));
        assertTrue(book.bestBuy().isEmpty());
    }

    @Test
    void updateOrderReSortsAfterPriceChange() {
        Order low = buy("1", "100", at(0));
        book.addOrder(low);
        book.addOrder(buy("1", "101", at(1)));

        book.updateOrder(low.id(), b -> b.price(new BigDecimal("102")));

        assertEquals(low.id(), book.bestBuy().orElseThrow().id());
    }

    @Test
    void updateOrderMovesOrderWhenSideChanges() {
        Order order = buy("1", "100", at(0));
        book.addOrder(order);

        book.updateOrder(order.id(), b -> b.side(Side.SELL));

        assertTrue(book.buyOrders().isEmpty());
        assertEquals(order.id(), book.bestSell().orElseThrow().id());
    }

    @Test
    void updateOrderRejectsUnknownId() {
        assertThrows(NoSuchElementException.class,
                () -> book.updateOrder(base().build().id(), b -> b.price(new BigDecimal("1"))));
    }

    @Test
    void spreadIsBestSellMinusBestBuy() {
        book.addOrder(buy("1", "100", at(0)));
        book.addOrder(sell("1", "105", at(1)));

        assertEquals(0, book.spread().orElseThrow().compareTo(new BigDecimal("5")));
    }

    @Test
    void spreadIsEmptyWhenEitherSideIsEmpty() {
        assertEquals(Optional.empty(), book.spread());

        book.addOrder(buy("1", "100", at(0)));
        assertEquals(Optional.empty(), book.spread());
    }

    @Test
    void marketOrderRestsAheadOfHigherPricedLimit() {
        book.addOrder(buy("1", "1000", at(0)));       // a very aggressive limit bid
        Order market = marketBuy("1", at(1));
        book.addOrder(market);

        assertSame(market, book.bestBuy().orElseThrow()); // market still ranks first
    }

    @Test
    void restingMarketOrdersAreOrderedByArrivalTime() {
        Order earlier = marketBuy("1", at(0));
        book.addOrder(marketBuy("1", at(5)));
        book.addOrder(earlier);

        assertSame(earlier, book.bestBuy().orElseThrow());
    }

    @Test
    void spreadIsEmptyWhenTopOfBookIsAMarketOrder() {
        book.addOrder(marketBuy("1", at(0)));
        book.addOrder(sell("1", "105", at(1)));

        assertEquals(Optional.empty(), book.spread());
    }

    @Test
    void availableLiquidityIsZeroOnAnEmptySide() {
        assertEquals(0, book.availableLiquidity(Side.SELL).compareTo(BigDecimal.ZERO));
        assertEquals(0, book.availableLiquidity(Side.SELL, new BigDecimal("100")).compareTo(BigDecimal.ZERO));
    }

    @Test
    void availableLiquiditySumsEveryOrderOnTheSide() {
        book.addOrder(sell("3", "100", at(0)));
        book.addOrder(sell("4", "101", at(1)));
        book.addOrder(buy("9", "99", at(2))); // other side, must not count

        assertEquals(0, book.availableLiquidity(Side.SELL).compareTo(new BigDecimal("7")));
        assertEquals(0, book.availableLiquidity(Side.BUY).compareTo(new BigDecimal("9")));
    }

    @Test
    void priceLimitedLiquidityCountsOnlyOrdersTheAggressorCanReach() {
        book.addOrder(sell("3", "100", at(0)));
        book.addOrder(sell("4", "101", at(1)));
        book.addOrder(sell("5", "102", at(2)));

        // A buyer limited to 101 can reach the 100 and 101 asks, but not the 102 ask.
        assertEquals(0, book.availableLiquidity(Side.SELL, new BigDecimal("101"))
                .compareTo(new BigDecimal("7")));
    }

    @Test
    void priceLimitedLiquidityOnBuySideCountsBidsAtOrAboveTheLimit() {
        book.addOrder(buy("3", "100", at(0)));
        book.addOrder(buy("4", "99", at(1)));
        book.addOrder(buy("5", "98", at(2)));

        // A seller limited to 99 can hit the 100 and 99 bids, but not the 98 bid.
        assertEquals(0, book.availableLiquidity(Side.BUY, new BigDecimal("99"))
                .compareTo(new BigDecimal("7")));
    }

    @Test
    void priceLimitedLiquidityIncludesRestingMarketOrders() {
        book.addOrder(com.oms.support.TestOrders.marketSell("2", at(0)));
        book.addOrder(sell("3", "100", at(1)));
        book.addOrder(sell("4", "105", at(2))); // beyond the aggressor's limit

        // Market orders trade at any price, so they always count.
        assertEquals(0, book.availableLiquidity(Side.SELL, new BigDecimal("100"))
                .compareTo(new BigDecimal("5")));
    }

    @Test
    void rejectsRestingAStopOrderOnTheBook() {
        assertThrows(IllegalArgumentException.class, () -> book.addOrder(stopBuy("1", "100", at(0))));
    }

    @Test
    void rejectsParkingANonStopOrderInTheStopLists() {
        assertThrows(IllegalArgumentException.class, () -> book.addStopOrder(buy("1", "100", at(0))));
    }

    @Test
    void pollReleasesOnlyBuyStopsAtOrBelowTheTradedPrice() {
        Order nearest = stopBuy("1", "100", at(0));
        Order mid = stopBuy("1", "101", at(1));
        Order far = stopBuy("1", "103", at(2));
        book.addStopOrder(far);
        book.addStopOrder(nearest);
        book.addStopOrder(mid);

        List<Order> triggered = book.pollTriggeredStops(new BigDecimal("101"));

        assertEquals(List.of(nearest, mid), triggered);          // closest trigger first
        assertEquals(1, book.buyStopOrders().size());            // far stop stays parked
        assertTrue(book.pollTriggeredStops(new BigDecimal("101")).isEmpty()); // polled stops are gone
    }

    @Test
    void pollReleasesOnlySellStopsAtOrAboveTheTradedPrice() {
        Order nearest = stopSell("1", "100", at(0));
        Order mid = stopSell("1", "99", at(1));
        Order far = stopSell("1", "97", at(2));
        book.addStopOrder(far);
        book.addStopOrder(nearest);
        book.addStopOrder(mid);

        List<Order> triggered = book.pollTriggeredStops(new BigDecimal("99"));

        assertEquals(List.of(nearest, mid), triggered);          // highest trigger fires first
        assertEquals(1, book.sellStopOrders().size());
    }

    @Test
    void stopsAtTheSameTriggerPriceReleaseInArrivalOrder() {
        Order later = stopBuy("1", "100", at(5));
        Order earlier = stopBuy("1", "100", at(0));
        book.addStopOrder(later);
        book.addStopOrder(earlier);

        assertEquals(List.of(earlier, later), book.pollTriggeredStops(new BigDecimal("100")));
    }

    @Test
    void removeStopOrderReportsWhetherPresent() {
        Order stop = stopSell("1", "95", at(0));
        book.addStopOrder(stop);

        assertTrue(book.removeStopOrder(stop));
        assertFalse(book.removeStopOrder(stop));
        assertTrue(book.pollTriggeredStops(new BigDecimal("90")).isEmpty());
    }

    @Test
    void exposedViewsAreUnmodifiable() {
        Order order = buy("1", "100", at(0));
        book.addOrder(order);

        assertThrows(UnsupportedOperationException.class,
                () -> book.buyOrders().remove(order.id()));
        assertThrows(UnsupportedOperationException.class,
                () -> book.sellOrders().clear());
    }
}
