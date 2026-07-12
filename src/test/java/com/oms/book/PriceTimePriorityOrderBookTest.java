package com.oms.book;

/** Runs the {@link OrderBookContractTest} against the TreeMap-backed book. */
class PriceTimePriorityOrderBookTest extends OrderBookContractTest {

    @Override
    protected OrderBook createBook() {
        return new PriceTimePriorityOrderBook();
    }
}
