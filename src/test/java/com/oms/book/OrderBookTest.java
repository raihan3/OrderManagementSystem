package com.oms.book;

import com.oms.model.Order;
import com.oms.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.oms.support.TestOrders.at;
import static com.oms.support.TestOrders.base;
import static com.oms.support.TestOrders.buy;
import static com.oms.support.TestOrders.sell;
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
    void exposedViewsAreUnmodifiable() {
        Order order = buy("1", "100", at(0));
        book.addOrder(order);

        assertThrows(UnsupportedOperationException.class,
                () -> book.buyOrders().remove(order.id()));
        assertThrows(UnsupportedOperationException.class,
                () -> book.sellOrders().clear());
    }
}
