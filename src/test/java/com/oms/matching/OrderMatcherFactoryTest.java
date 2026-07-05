package com.oms.matching;

import com.oms.book.OrderBook;
import com.oms.model.MatchResult;
import com.oms.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.oms.support.TestOrders.at;
import static com.oms.support.TestOrders.buy;
import static com.oms.support.TestOrders.fokBuy;
import static com.oms.support.TestOrders.iocBuy;
import static com.oms.support.TestOrders.marketBuy;
import static com.oms.support.TestOrders.sell;
import static com.oms.support.TestOrders.stopBuy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderMatcherFactoryTest {

    private OrderMatcherFactory factory;

    @BeforeEach
    void setUp() {
        factory = new OrderMatcherFactory(Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void returnsDistinctMatchersPerOrderType() {
        OrderMatcher forLimit = factory.matcherFor(buy("1", "100", at(0)));
        OrderMatcher forMarket = factory.matcherFor(marketBuy("1", at(0)));
        OrderMatcher forIoc = factory.matcherFor(iocBuy("1", "100", at(0)));
        OrderMatcher forFok = factory.matcherFor(fokBuy("1", "100", at(0)));

        assertNotSame(forLimit, forMarket);
        assertNotSame(forLimit, forIoc);
        assertNotSame(forMarket, forIoc);
        assertNotSame(forIoc, forFok);
        assertNotSame(forLimit, forFok);
    }

    @Test
    void fokMatcherKillsWithoutTradingWhenLiquidityIsShort() {
        OrderBook book = new OrderBook();
        book.addOrder(sell("2", "100", at(0)));
        Order incoming = fokBuy("5", "100", at(1));

        MatchResult result = factory.matcherFor(incoming).match(incoming, book);

        assertTrue(result.trades().isEmpty());
        assertFalse(result.hasRestingRemainder());
        // The resting sell was not consumed at all.
        assertTrue(book.bestSell().isPresent());
    }

    @Test
    void iocMatcherCancelsUnfilledRemainder() {
        OrderBook book = new OrderBook();
        Order incoming = iocBuy("5", "100", at(0));

        MatchResult result = factory.matcherFor(incoming).match(incoming, book);

        assertFalse(result.hasRestingRemainder());
        assertTrue(result.remainder().isPresent());
        assertTrue(book.bestBuy().isEmpty());
    }

    @Test
    void reusesTheSameMatcherInstanceForAGivenType() {
        assertSame(factory.matcherFor(buy("1", "100", at(0))), factory.matcherFor(sell("1", "101", at(1))));
    }

    @Test
    void limitMatcherRestsUnfilledRemainder() {
        OrderBook book = new OrderBook();
        Order incoming = buy("5", "100", at(0));

        MatchResult result = factory.matcherFor(incoming).match(incoming, book);

        assertTrue(result.hasRestingRemainder());
        assertSame(incoming.id(), book.bestBuy().orElseThrow().id());
    }

    @Test
    void marketMatcherRestsUnfilledRemainderAtTopOfBook() {
        OrderBook book = new OrderBook();
        Order incoming = marketBuy("5", at(0));

        MatchResult result = factory.matcherFor(incoming).match(incoming, book);

        assertTrue(result.hasRestingRemainder());
        assertSame(incoming.id(), book.bestBuy().orElseThrow().id());
    }

    @Test
    void rejectsDormantStopOrders() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.matcherFor(stopBuy("1", "100", at(0))));
    }

    @Test
    void rejectsNullOrder() {
        assertThrows(NullPointerException.class, () -> factory.matcherFor(null));
    }

    @Test
    void constructorRejectsNullClock() {
        assertThrows(NullPointerException.class, () -> new OrderMatcherFactory(null));
    }
}
