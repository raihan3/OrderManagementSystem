package com.oms.engine;

import com.oms.book.OrderBook;
import com.oms.book.OrderBookRegistry;
import com.oms.model.MatchResult;
import com.oms.model.Order;
import com.oms.model.OrderStatus;
import com.oms.model.OrderType;
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
import static com.oms.support.TestOrders.fokBuy;
import static com.oms.support.TestOrders.iocBuy;
import static com.oms.support.TestOrders.marketBuy;
import static com.oms.support.TestOrders.marketSell;
import static com.oms.support.TestOrders.sell;
import static com.oms.support.TestOrders.stopBuy;
import static com.oms.support.TestOrders.stopLimitBuy;
import static com.oms.support.TestOrders.stopSell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderMatchingServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-04T15:30:00Z");

    private OrderBook book;
    private OrderMatchingService service;

    @BeforeEach
    void setUp() {
        OrderBookRegistry registry = new OrderBookRegistry();
        service = new OrderMatchingService(registry, Clock.fixed(NOW, ZoneOffset.UTC));
        // Existing single-symbol tests operate on the AAPL book (TestOrders default symbol).
        book = registry.bookFor("AAPL");
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
    void marketBuyFillsAgainstBestSellRegardlessOfPrice() {
        book.addOrder(sell("5", "100", at(0)));

        MatchResult result = service.match(marketBuy("5", at(1)));

        assertEquals(1, result.trades().size());
        assertTrue(result.fullyFilled());
        assertEquals(0, result.trades().getFirst().price().compareTo(new BigDecimal("100")));
        assertTrue(book.bestSell().isEmpty());
    }

    @Test
    void marketBuyWalksMultiplePriceLevels() {
        book.addOrder(sell("2", "100", at(0)));
        book.addOrder(sell("3", "101", at(1)));

        MatchResult result = service.match(marketBuy("5", at(2)));

        assertEquals(2, result.trades().size());
        assertTrue(result.fullyFilled());
        assertEquals(0, result.trades().get(0).price().compareTo(new BigDecimal("100")));
        assertEquals(0, result.trades().get(1).price().compareTo(new BigDecimal("101")));
    }

    @Test
    void marketSellFillsAgainstBestBuy() {
        Order restingBuy = buy("5", "100", at(0));
        book.addOrder(restingBuy);

        MatchResult result = service.match(marketSell("5", at(1)));

        assertTrue(result.fullyFilled());
        assertEquals(restingBuy.id(), result.trades().getFirst().buyOrderId());
    }

    @Test
    void marketOrderRestsUnfilledRemainderAtTopOfBook() {
        book.addOrder(sell("3", "100", at(0)));

        MatchResult result = service.match(marketBuy("5", at(1)));

        assertEquals(1, result.trades().size());
        assertFalse(result.fullyFilled());
        assertTrue(result.hasRestingRemainder());

        Order remainder = result.remainder().orElseThrow();
        assertEquals(OrderStatus.PENDING, remainder.status());
        assertTrue(remainder.isMarket());
        assertEquals(0, remainder.quantity().compareTo(new BigDecimal("2")));
        // The remainder now rests at the top of the buy book.
        assertEquals(remainder.id(), book.bestBuy().orElseThrow().id());
    }

    @Test
    void marketOrderIntoEmptyBookRestsFully() {
        Order incoming = marketBuy("5", at(0));
        MatchResult result = service.match(incoming);

        assertTrue(result.trades().isEmpty());
        assertFalse(result.fullyFilled());
        assertEquals(OrderStatus.PENDING, result.remainder().orElseThrow().status());
        assertEquals(incoming.id(), book.bestBuy().orElseThrow().id());
    }

    @Test
    void restingMarketOrderIsFilledFirstAtIncomingLimitPrice() {
        // A market buy rests with no liquidity, then a limit sell arrives.
        Order restingMarketBuy = marketBuy("5", at(0));
        service.match(restingMarketBuy);

        MatchResult result = service.match(sell("5", "100", at(1)));

        assertEquals(1, result.trades().size());
        assertTrue(result.fullyFilled());
        // The resting market buy is the buyer, priced at the incoming limit sell's price.
        assertEquals(restingMarketBuy.id(), result.trades().getFirst().buyOrderId());
        assertEquals(0, result.trades().getFirst().price().compareTo(new BigDecimal("100")));
        assertTrue(book.bestBuy().isEmpty());
    }

    @Test
    void opposingMarketOrdersWithoutReferencePriceBothRest() {
        // Cold start: the symbol has never traded, so there is no reference price.
        Order restingMarketBuy = marketBuy("5", at(0));
        service.match(restingMarketBuy);

        MatchResult result = service.match(marketSell("5", at(1)));

        assertTrue(result.trades().isEmpty());
        assertFalse(result.fullyFilled());
        // Both market orders rest on their respective sides, awaiting a price to be established.
        assertTrue(book.bestBuy().orElseThrow().isMarket());
        assertTrue(book.bestSell().orElseThrow().isMarket());
    }

    @Test
    void opposingMarketOrdersTradeAtTrackedMarketPrice() {
        service.marketPriceTracker().record("AAPL", new BigDecimal("100"));

        Order restingMarketBuy = marketBuy("5", at(0));
        service.match(restingMarketBuy);
        MatchResult result = service.match(marketSell("5", at(1)));

        assertEquals(1, result.trades().size());
        assertTrue(result.fullyFilled());

        Trade trade = result.trades().getFirst();
        assertEquals(restingMarketBuy.id(), trade.buyOrderId());
        assertEquals(0, trade.price().compareTo(new BigDecimal("100")));
        assertTrue(book.bestBuy().isEmpty());
        assertTrue(book.bestSell().isEmpty());
    }

    @Test
    void everyTradeUpdatesTheTrackedMarketPrice() {
        book.addOrder(sell("5", "100", at(0)));
        service.match(buy("5", "100", at(1)));

        assertEquals(0, service.marketPriceTracker().lastPrice("AAPL").orElseThrow()
                .compareTo(new BigDecimal("100")));
    }

    @Test
    void iocOrderFillsWhatItCanAndCancelsTheRemainder() {
        book.addOrder(sell("3", "100", at(0)));

        MatchResult result = service.match(iocBuy("5", "100", at(1)));

        assertEquals(1, result.trades().size());
        assertFalse(result.fullyFilled());
        assertFalse(result.hasRestingRemainder());

        Order remainder = result.remainder().orElseThrow();
        assertEquals(OrderStatus.CANCELLED, remainder.status());
        assertEquals(0, remainder.quantity().compareTo(new BigDecimal("2")));
        // Nothing from the IOC order rests on the book.
        assertTrue(book.bestBuy().isEmpty());
    }

    @Test
    void iocOrderFullyFilledLeavesNoRemainder() {
        book.addOrder(sell("5", "100", at(0)));

        MatchResult result = service.match(iocBuy("5", "100", at(1)));

        assertEquals(1, result.trades().size());
        assertTrue(result.fullyFilled());
        assertTrue(book.bestBuy().isEmpty());
    }

    @Test
    void iocOrderIntoEmptyBookIsFullyCancelled() {
        MatchResult result = service.match(iocBuy("5", "100", at(0)));

        assertTrue(result.trades().isEmpty());
        assertFalse(result.fullyFilled());
        assertEquals(OrderStatus.CANCELLED, result.remainder().orElseThrow().status());
        assertTrue(book.bestBuy().isEmpty());
    }

    @Test
    void iocOrderRespectsItsLimitPrice() {
        book.addOrder(sell("5", "101", at(0))); // ask above the IOC bid

        MatchResult result = service.match(iocBuy("5", "100", at(1)));

        assertTrue(result.trades().isEmpty());
        assertFalse(result.fullyFilled());
        assertEquals(OrderStatus.CANCELLED, result.remainder().orElseThrow().status());
        // The resting sell is untouched.
        assertEquals(0, book.bestSell().orElseThrow().quantity().compareTo(new BigDecimal("5")));
    }

    @Test
    void fokOrderFillsCompletelyWhenLiquiditySuffices() {
        book.addOrder(sell("3", "100", at(0)));
        book.addOrder(sell("2", "100", at(1)));

        MatchResult result = service.match(fokBuy("5", "100", at(2)));

        assertEquals(2, result.trades().size());
        assertTrue(result.fullyFilled());
        assertTrue(book.bestSell().isEmpty());
    }

    @Test
    void fokOrderFillsAcrossPriceLevelsWithinItsLimit() {
        book.addOrder(sell("2", "100", at(0)));
        book.addOrder(sell("3", "101", at(1)));

        MatchResult result = service.match(fokBuy("5", "101", at(2)));

        assertEquals(2, result.trades().size());
        assertTrue(result.fullyFilled());
        assertEquals(0, result.trades().get(0).price().compareTo(new BigDecimal("100")));
        assertEquals(0, result.trades().get(1).price().compareTo(new BigDecimal("101")));
    }

    @Test
    void fokOrderKillsEntirelyWhenLiquidityIsInsufficient() {
        book.addOrder(sell("3", "100", at(0)));

        MatchResult result = service.match(fokBuy("5", "100", at(1)));

        // No partial fill: zero trades and the whole quantity cancelled.
        assertTrue(result.trades().isEmpty());
        assertFalse(result.fullyFilled());
        assertFalse(result.hasRestingRemainder());
        Order remainder = result.remainder().orElseThrow();
        assertEquals(OrderStatus.CANCELLED, remainder.status());
        assertEquals(0, remainder.quantity().compareTo(new BigDecimal("5")));

        // The book is completely untouched: the resting sell keeps its full quantity.
        assertEquals(0, book.bestSell().orElseThrow().quantity().compareTo(new BigDecimal("3")));
        assertTrue(book.bestBuy().isEmpty());
    }

    @Test
    void fokOrderIgnoresLiquidityBeyondItsLimitPrice() {
        book.addOrder(sell("3", "100", at(0)));
        book.addOrder(sell("5", "102", at(1))); // enough quantity, but priced beyond the FOK limit

        MatchResult result = service.match(fokBuy("5", "100", at(2)));

        assertTrue(result.trades().isEmpty());
        assertEquals(OrderStatus.CANCELLED, result.remainder().orElseThrow().status());
        // Both resting sells remain fully intact.
        assertEquals(0, book.availableLiquidity(com.oms.model.Side.SELL).compareTo(new BigDecimal("8")));
    }

    @Test
    void fokOrderIntoEmptyBookIsKilled() {
        MatchResult result = service.match(fokBuy("5", "100", at(0)));

        assertTrue(result.trades().isEmpty());
        assertEquals(OrderStatus.CANCELLED, result.remainder().orElseThrow().status());
        assertTrue(book.bestBuy().isEmpty());
    }

    @Test
    void fokOrderCanFillAgainstRestingMarketOrders() {
        service.match(marketSell("3", at(0))); // rests atop the sell side
        book.addOrder(sell("2", "100", at(1)));

        MatchResult result = service.match(fokBuy("5", "100", at(2)));

        assertEquals(2, result.trades().size());
        assertTrue(result.fullyFilled());
        // The market sell trades at the FOK order's price.
        assertEquals(0, result.trades().getFirst().price().compareTo(new BigDecimal("100")));
    }

    @Test
    void stopOrderParksWhenNoMarketPriceExists() {
        Order stop = stopBuy("2", "100", at(0));

        MatchResult result = service.match(stop);

        assertTrue(result.trades().isEmpty());
        assertEquals(stop, result.remainder().orElseThrow());
        assertEquals(1, book.buyStopOrders().size());
        assertTrue(book.bestBuy().isEmpty()); // parked, not resting on the book
    }

    @Test
    void sellStopParksThenActivatesAsMarketOrderWhenPriceFallsToTrigger() {
        establishLastPriceAt("100");

        Order stop = stopSell("4", "95", at(2));
        assertTrue(service.match(stop).trades().isEmpty()); // 100 > 95: parked

        book.addOrder(buy("3", "95", at(3)));
        book.addOrder(buy("4", "94", at(4)));

        // This sell trades at 95, firing the stop; its activation (a market sell for 4)
        // then consumes the 94 bid.
        MatchResult result = service.match(sell("3", "95", at(5)));

        assertEquals(1, result.trades().size()); // the triggering order's own fill only
        assertTrue(book.sellStopOrders().isEmpty());
        assertTrue(book.bestBuy().isEmpty()); // both bids consumed
        assertEquals(0, service.marketPriceTracker().lastPrice("AAPL").orElseThrow()
                .compareTo(new BigDecimal("94")));
    }

    @Test
    void buyStopLimitActivatesAsLimitOrderAtItsLimitPrice() {
        establishLastPriceAt("100");

        Order stop = stopLimitBuy("5", "105", "106", at(2));
        assertTrue(service.match(stop).trades().isEmpty()); // 100 < 105: parked

        book.addOrder(sell("2", "105", at(3)));
        service.match(buy("2", "105", at(4))); // trades at 105, firing the stop

        // The activated order rests on the book as a LIMIT buy at its limit price of 106.
        Order activated = book.bestBuy().orElseThrow();
        assertEquals(stop.id(), activated.id());
        assertEquals(OrderType.LIMIT, activated.type());
        assertEquals(0, activated.price().compareTo(new BigDecimal("106")));
        assertEquals(0, activated.quantity().compareTo(new BigDecimal("5")));
        assertNull(activated.stopPrice());
        assertTrue(book.buyStopOrders().isEmpty());
    }

    @Test
    void stopOrderAlreadyTriggeredOnArrivalActivatesImmediately() {
        establishLastPriceAt("100");
        book.addOrder(buy("4", "99", at(2)));

        // A sell stop at 100 while the market last traded at 100 is already triggered.
        MatchResult result = service.match(stopSell("4", "100", at(3)));

        assertEquals(1, result.trades().size());
        assertTrue(result.fullyFilled());
        assertEquals(0, result.trades().getFirst().price().compareTo(new BigDecimal("99")));
        assertTrue(book.sellStopOrders().isEmpty()); // never parked
    }

    @Test
    void stopActivationsCascadeIntoFurtherStopTriggers() {
        establishLastPriceAt("100");

        service.match(stopSell("2", "95", at(2))); // stop A
        service.match(stopSell("2", "94", at(3))); // stop B, only reachable via A's fills

        book.addOrder(buy("2", "95", at(4)));
        book.addOrder(buy("2", "94", at(5)));
        book.addOrder(buy("2", "93", at(6)));

        // Trades at 95 → fires A; A's market sell trades at 94 → fires B; B trades at 93.
        service.match(sell("2", "95", at(7)));

        assertTrue(book.sellStopOrders().isEmpty());
        assertTrue(book.bestBuy().isEmpty());
        assertEquals(0, service.marketPriceTracker().lastPrice("AAPL").orElseThrow()
                .compareTo(new BigDecimal("93")));
    }

    /** Prints a trade at the given price so the symbol has a last-traded (trigger) price. */
    private void establishLastPriceAt(String price) {
        book.addOrder(sell("1", price, at(0)));
        service.match(buy("1", price, at(1)));
    }

    @Test
    void ordersForDifferentSymbolsDoNotCross() {
        // A resting MSFT sell that would cross on price if symbols were ignored.
        OrderBook msftBook = service.orderBook("MSFT");
        msftBook.addOrder(sell("MSFT", "5", "100", at(0)));

        // An AAPL buy at the same price must not touch the MSFT book.
        MatchResult result = service.match(buy("AAPL", "5", "100", at(1)));

        assertTrue(result.trades().isEmpty());
        assertFalse(result.fullyFilled());
        assertEquals(0, msftBook.bestSell().orElseThrow().quantity().compareTo(new BigDecimal("5")));
        assertEquals(0, service.orderBook("AAPL").bestBuy().orElseThrow().quantity().compareTo(new BigDecimal("5")));
    }

    @Test
    void ordersForTheSameSymbolMatchInTheirOwnBook() {
        service.orderBook("MSFT").addOrder(sell("MSFT", "5", "250", at(0)));

        MatchResult result = service.match(buy("MSFT", "5", "250", at(1)));

        assertEquals(1, result.trades().size());
        assertTrue(result.fullyFilled());
        assertEquals("MSFT", result.trades().getFirst().symbol());
        assertTrue(service.orderBook("MSFT").bestSell().isEmpty());
    }

    @Test
    void defaultConstructorProvidesPerSymbolBooks() {
        OrderMatchingService standalone = new OrderMatchingService();

        assertSame(standalone.orderBook("AAPL"), standalone.orderBook("AAPL"));
        assertTrue(standalone.orderBook("AAPL").bestBuy().isEmpty());
    }

    @Test
    void rejectsNullIncomingOrder() {
        assertThrows(NullPointerException.class, () -> service.match(null));
    }
}
